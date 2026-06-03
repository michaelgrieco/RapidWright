/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.blocks.*;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.tools.*;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.edif.*;
import com.xilinx.rapidwright.interchange.*;
import com.xilinx.rapidwright.router.*;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.ClkRouteTiming;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.*;
import com.xilinx.rapidwright.bitstream.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Map.Entry;
import static java.util.Map.entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RWRoute class provides the main methods for routing a design.
 * Creating a RWRoute Object needs a {@link Design} Object and a {@link RWRouteConfig} Object.
 */
public class ComposablePAT {

    public static void SetInitParameter(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("USAGE: <input.dcp> <input.bs> <output.dcp> <output.bs>");
            return;
        }

        System.out.printf("Reading %s and writing to %s\n", args[1], args[3]);

        Design design = Design.readCheckpoint(args[0]);
        Bitstream bitstream = Bitstream.readBitstream(args[1]);
        ConfigArray configArray = bitstream.configureArray();
        // Changes the initialization of the FF to 1
        Cell cell = design.getCell("bitfusion_i/composable_mesh_0/inst/cluster/c/g_pe_rows[0].g_pe_cols[1].t/pe/const_o_router_sel_east[0]");
        cell.getProperty("INIT").setValue("1");
        configArray.updateUserStateBits(cell);
        bitstream.updatePacketsFromConfigArray();
        design.writeCheckpoint(args[2]);
        bitstream.writeBitstream(args[3]);
    }

    public static final int NUM_PES = 16;
    public static final int NUM_PE_ROWS = 4;
    public static final int NUM_PE_COLS = 4;
    public static final int[][] TRANSFORM_ARGS = {
        {0, 0},
        {-60, 0   },
        {-120, 0  },
        {-180, 0  },
        {0, 5     },
        {-60, 5   },
        {-120, 5  },
        {-180, 5  },
        {0, 8     },
        {-60, 8   },
        {-120, 8  },
        {-180, 8  },
        {0, 11    },
        {-60, 11  },
        {-120, 11 },
        {-180, 11 }
    };

    public static void copyBlueprint(String[] args) throws Exception {

        if (args.length < 4) {
            System.out.println("USAGE: <dcp_path> <blueprint_pblock> <blueprint_range> <blueprint_idx>");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("ComposablePAT", true);

        // ===========================
        // ===== Parse arguments =====
        // ===========================

        String blueprint_pblock = args[1];
        String blueprint_pblock_range = args[2];
        String blueprint_dcp_path = args[0] + "/" + blueprint_pblock + "_processing_element_identity_route_design.dcp";
        int blueprint_idx = Integer.parseInt(args[3]);
        if (blueprint_idx >= NUM_PES) {
            System.out.printf("ERROR: blueprint index, %d, is too large (>= %d)\n", blueprint_idx, NUM_PES);
        }

        // =======================================
        // ===== Get blueprint pin locations =====
        // =======================================

        t.start("load_dcp");
        Design design = Design.readCheckpoint(blueprint_dcp_path);
        Module module = new Module(design);
        PBlock pblock = new PBlock(design.getDevice(), blueprint_pblock_range);
        Collection<Port> ports = module.getPorts();
        Port clk = module.getPort("clk");
        System.out.printf("Module %s has %d ports, clock is %s\n", module.toString(), module.getPorts().size(), (clk == null ? "NULL" : clk.toString()));

        //for (Cell c : module.getCells()) {
        //    System.out.printf("Cell is %s\n", c.toString());
        //}

        HashMap<String,Float> clks = module.getClocks();
        for (Map.Entry<String,Float> entry : clks.entrySet()) {
            System.out.printf("  Clock %s => %f\n", entry.getKey(), entry.getValue());
        }

        for (Port p : ports) {
            System.out.printf("Port %s => %s, %s\n", p.toString(), p.getPartitionPinLoc().toString(), p.getSingleSitePinInstName());
        }
        t.stop("load_dcp");

        // =================================
        // ===== Transform each region =====
        // =================================

        int dx_pblock = 0;
        int dx_relocate = 0;
        int dy_pblock = 0;
        int dy_relocate = 0;

        String dcp_path;
        String pblock_str;
        String tcl;


        int i = 0;
        for (int c = 0; c < NUM_PE_COLS; ++c) {
            for (int r = 0; r < NUM_PE_ROWS; ++r) {

                // load design
                t.start("load_dcp");
                design = Design.readCheckpoint(blueprint_dcp_path);
                module = new Module(design);
                int[] og_offset = TRANSFORM_ARGS[blueprint_idx];
                //PBlock pblock = new PBlock(design.getDevice(), "SLICE_X79Y244:SLICE_X80Y295");
                pblock = new PBlock(design.getDevice(), blueprint_pblock_range);
                Site og_anchor = module.getAnchor(); // INT_X49Y184
                Tile og_tile = og_anchor.getTile();
                t.stop();

                // output properties
                pblock_str = "pblock_slot_0_" + Integer.toString(r) + "_" + Integer.toString(c);
                dcp_path = args[0] + "/" + pblock_str + "_processing_element_identity_route_design.transformed.dcp";

                // transform to new location
                if (i != blueprint_idx) {
                    t.start("transform");

                    // calculate deltas in coordinates
                    dx_relocate = TRANSFORM_ARGS[i][1] - og_offset[1];
                    dy_relocate = TRANSFORM_ARGS[i][0] - og_offset[0];
                    Tile new_tile = og_tile.getTileXYNeighbor(dx_relocate, dy_relocate);
                    if (new_tile == null) {
                        System.out.printf("Could not find new tile (from %s/%s) %d @ row %d and column %d (%d, %d)\n", og_anchor.toString(), og_tile.toString(), i, r, c, dx_relocate, dy_relocate);
                        return;
                    }
                    dx_pblock = new_tile.getColumn() - og_tile.getColumn();
                    dy_pblock = new_tile.getRow() - og_tile.getRow();

                    System.out.printf("(%d %d %d): Relocating %s with deltas (%d %d; %d %d) to pblock %s: ", i, r, c, pblock.toString(), dx_relocate, dx_pblock, dy_relocate, dy_pblock, pblock_str);

                    // translate design
                    RelocationTools.relocate(design, pblock, dx_relocate, dy_relocate);
                    pblock.movePBlock(dx_pblock, dy_pblock);
                    System.out.println(pblock.toString());

                    // modify pblock
                    tcl = "delete_pblocks [get_pblocks]";
                    design.addXDCConstraint(ConstraintGroup.LATE, tcl);
                    tcl = "create_pblock " + pblock_str;
                    design.addXDCConstraint(ConstraintGroup.LATE, tcl);
                    tcl = "add_cells_to_pblock [get_pblocks " + pblock + "] [get_cells *]";
                    design.addXDCConstraint(ConstraintGroup.LATE, tcl);
                    tcl = "resize_pblock [get_pblocks " + pblock_str + "] -add { " + pblock.toString() + " }";
                    design.addXDCConstraint(ConstraintGroup.LATE, tcl);

                    t.stop();
                }

                // write output DCP
                t.stop().start("write_dcp");
                design.setAutoIOBuffers(false);
                design.writeCheckpoint(dcp_path, CodePerfTracker.SILENT);
                t.stop();

                ++i;
            }
        }

        t.stop().printSummary();

        System.out.printf("load_dcp: %d\n", t.getRuntime("load_dcp"));
        System.out.printf("transform: %d\n", t.getRuntime("transform"));
        System.out.printf("write_dcp: %d\n", t.getRuntime("write_dcp"));

    }

    // =====================
    // ===== constants =====
    // =====================

    public static final int MESH_ROWS = 4;
    public static final int MESH_COLS = 4;

    // slice x-coordinate boundaries of regions between the
    public static final int[][] GUTTER_BOUNDS_SLICE_X = {
        // x0, x1
        {73, 78},
        {81, 83},
        {86, 88}
    };

    public static final int[] PE_TILE_X = {
        44,
        49,
        52,
        55
    };

    // ===========================
    // ===== parse arguments =====
    // ===========================

    private String blueprint_pblock;
    private String blueprint_pblock_range;
    private String static_dcp_path;
    private String static_edif_path;
    private String static_netlist_path;
    private String routed_blueprint_dcp_path;
    private String routed_dcp_path;
    private String inter_pe_nets_path;
    private int blueprint_idx;

    public ComposablePAT(String[] args) {

        if (args.length < 4) {
            System.out.println("USAGE: <dcp_path> <blueprint_pblock> <blueprint_range> <blueprint_idx>");
            return;
        }

        blueprint_pblock = args[1];
        blueprint_pblock_range = args[2];
        static_dcp_path = args[0] + "/" + "bitfusion_wrapper_route_design_clock.dcp";
        static_edif_path = args[0] + "/" + "bitfusion_wrapper_route_design_clock.edif";
        routed_blueprint_dcp_path = args[0] + "/" + "bitfusion_wrapper_route_design_blueprint.dcp";
        routed_dcp_path = args[0] + "/" + "bitfusion_wrapper_route_design_inter.dcp";
        static_netlist_path = args[0] + "/" + "bitfusion_wrapper_route_design_inter.edif";
        inter_pe_nets_path = args[0] + "/" + "rapidwright_nets.txt";
        blueprint_idx = Integer.parseInt(args[3]);
        if (blueprint_idx >= NUM_PES) {
            System.out.printf("ERROR: blueprint index, %d, is too large (>= %d)\n", blueprint_idx, NUM_PES);
        }
    }

    // get the SitePinInst corresponding to a placed cell
    private static SitePinInst getSitePinInst(Design design, String cell_path, String site_pin, boolean is_driver, Net net) {
        // get cell and placed site
        Cell cell = design.getCell(cell_path);
        if (cell == null) {
            return null;
        }
        SiteInst site_inst = cell.getSiteInst();

        // get pin in site
        SitePinInst pin_inst = site_inst.getSitePinInst(site_pin);
        if (pin_inst == null) {
            pin_inst = new SitePinInst(is_driver, site_pin, site_inst);
        }

        // associate pin with net
        if (is_driver) {
            net.setSource(pin_inst);
        }
        else {
            pin_inst.setNet(net);
        }

        return pin_inst;
    }

    // ===========================
    // ===== routing methods =====
    // ===========================

    private static String extendPBlock(Design design, String original_pblock, String new_driver_cell, String new_load_cell, int xpad_left, int xpad_right, int ypad) {
        String pblock = original_pblock;

        // get driver cell
        if (new_driver_cell != null) {
            Cell cell = design.getCell(new_driver_cell);
            SiteInst inst = cell.getSiteInst();
            Site site = inst.getSite();
            int x0 = site.getInstanceX();
            int y0 = site.getInstanceY();
            pblock += " " + getPBlockRange(x0-xpad_left, x0+xpad_right, y0-ypad, y0+ypad);
	    }

        // get load cell
        if (new_load_cell != null) {
            Cell cell = design.getCell(new_load_cell);
            SiteInst inst = cell.getSiteInst();
            Site site = inst.getSite();
            int x0 = site.getInstanceX();
            int y0 = site.getInstanceY();
            pblock += " " + getPBlockRange(x0-xpad_left, x0+xpad_right, y0-ypad, y0+ypad);
	    }

        return pblock;
    }

    private static String extendPBlock(Design design, String original_pblock, String new_driver_cell, String new_load_cell) {
        return extendPBlock(design, original_pblock, new_driver_cell, new_load_cell, 0, 0, 4);
    }

    // route a net from a source to a sink with specified pins
    private Net routeInterPENetPins(Design design, Router router, StaticConnection connection) {
        ArrayList<SitePinInst> targetPins = new ArrayList<SitePinInst>();

        // get net
        System.out.printf("Target net is %s\n", connection.net);
        Net targetNet = design.getNet(connection.net);
        System.out.printf("  => %s (%d)\n", targetNet == null ? "NULL" : targetNet.toString(), targetNet.getFanOut());
        for (SitePinInst i : targetNet.getSinkPins()) {
            System.out.printf("    => port %s\n", i.toString());
        }

        // get driver
        SitePinInst sourcePinInst = getSitePinInst(design, connection.source_cell, connection.source_site_pin, true, targetNet);

        // get loads
        Iterator<String> sinkCellIt = connection.sink_cells.iterator();
        Iterator<String> sinkSitePinIt = connection.sink_site_pins.iterator();
        String sinkCell = null;
        while (sinkCellIt.hasNext() && sinkSitePinIt.hasNext()) {
            sinkCell = sinkCellIt.next();
            String sinkSitePin = sinkSitePinIt.next();

            SitePinInst sinkPinInst = getSitePinInst(design, sinkCell, sinkSitePin, false, targetNet);
            targetPins.add(sinkPinInst);
        }

        // extend pblock to gutter and load cell
        String pblockRange = router.getRoutingPblock().toString();
        if (connection.net_format.contains("o_rstn")) {
            pblockRange = extendPBlock(design, pblockRange, connection.source_cell, sinkCell, 1, 0, 20);
	    }
        else {
            pblockRange = extendPBlock(design, pblockRange, null, sinkCell);
        }
        //router.setRoutingPblock(new PBlock(design.getDevice(), pblockRange));
        //RWRouteConfig router_config = new RWRouteConfig(new String[]{});
        //router_config.setPBlock(pblockRange);

        targetPins.add(sourcePinInst);
        System.out.printf("Routing to %d pins\n", targetPins.size());
        //System.out.printf("Routing from pin %s to pin %s (cell %s)\n", sourcePinInst.toString(), sinkPinInst.toString(), connection.sink_cell);

        //router.routePinsReEntrant(targetPins, false);

        String args[] = new String[] {
                    "--fixBoundingBox",
                    // use U-turn nodes and no masking of nodes cross RCLK
                    // Pros: maximum routability
                    // Con: might result in delay optimism and a slight increase in runtime
                    "--useUTurnNodes",
                    "--nonTimingDriven",
                    "--verbose",
                    "--pblock", pblockRange
                };
        System.out.printf("args:\n");
        for (String s : args) {
            System.out.printf("  %s\n", s);
        }

        //RWRoute rwrouter = new PartialRouter(design, router_config, targetPins);
        //rwrouter.preprocess();
        //rwrouter.initialize();
        //rwrouter.route();
        Design newDesign =
            PartialDFXRouter.routeDesignWithUserDefinedArguments(
                design,
                args,
                targetPins,
                false);
        System.out.printf("Done routing\n");
        newDesign.writeCheckpoint(routed_blueprint_dcp_path);

        connection.net_obj = targetNet;
        return targetNet;
    }

    // create a connection for a net with specified site pins and a partition node
    private static int connection_id = 0;
    private Design routeInterPENetPartition(Design design, String pblock, StaticConnection static_connection) {
        // create router
        RWRouteConfig router_config = new RWRouteConfig(new String[]{});
        router_config.setPBlock(extendPBlock(design, pblock, null, static_connection.sink_cell));
        //RWRoute router = new RWRoute(design, router_config);
        RouteNodeGraph graph = new RouteNodeGraph(design, router_config);

        // scope to one net
        Net targetNet = design.getNet(static_connection.net);
        NetWrapper netWrapper = new NetWrapper(connection_id++, targetNet);

        // get driver and load
        SitePinInst sourcePinInst = getSitePinInst(design, static_connection.source_cell, static_connection.source_site_pin, true, targetNet);
        SitePinInst sinkPinInst = getSitePinInst(design, static_connection.sink_cell, static_connection.sink_site_pin, false, targetNet);

        // create connection
        Connection c = new Connection(connection_id++, sourcePinInst, sinkPinInst, netWrapper);

        // add intermediate nodes to connection
        Collection<SitePinInst> pins = Collections.EMPTY_SET;
        if (static_connection.partition_node != null) {
            System.out.printf("Constraining net %s to route through node %s\n", targetNet.toString(), static_connection.partition_node.toString());
            c.addRnode(graph.getOrCreate(static_connection.partition_node));
            pins = Collections.singleton(c.getSource());
        }

        // perform routing
        // TODO focus on connection c or graph
        RWRoute router = new PartialRouter(design, router_config, pins);
        router.preprocess();
        router.initialize();
        router.route();

        return router.getDesign();

        // add partition pin from source
        // not necessary because routing from router
        //targetPins.add()
        // but must route from PE to router

        // add partition pin entering destination PE
        //SitePinInst partitionPinInst = new SitePinInst(false, sink_partition_pin);

        //SiteInst partitionSiteInst = design.createSiteInst(design.getDevice().getSite(sink_partition_pin_site));
        //Wire partitionWire = new Wire(design.getDevice(), sink_partition_pin);
        //Node partitionNode = partitionWire.getNode();
        // go from wire to site pin
        //SitePinInst partitionPinInst = new SitePinInst(false, xxx, partitionSiteInst);
        //SitePinInst sitePin = targetNet.createPin("A_O", siteInst);
        //Wire sink_node = new RouteNode(sink_wire.getTile(), sink_wire.getWireIndex());
    }

    // construct a PBlock range string from slice coordinates
    private static String getPBlockRange(int x0, int x1, int y0, int y1) {
        return "SLICE_X" + Integer.toString(x0)
                    + "Y" + Integer.toString(y0)
                    + ":SLICE_X" + Integer.toString(x1)
                    + "Y" + Integer.toString(y1);
    }

    // ========================
    // ===== flow methods =====
    // ========================

    private static class StaticConnection {

        // direction of travel
        public String direction;
        boolean crosses_pblock;

        // cell-specific nets and cells
        String net_format;
        String source_cell_format;
        String sink_cell_format;
        String net;
        String source_cell;
        String sink_cell;

        Collection<String> sink_cell_formats;
        Collection<String> sink_cells;
        Collection<String> sink_site_pins;

        // pins
        String source_site_pin;
        String sink_site_pin;

        // routed net object
        Net net_obj;

        // partition pin for receiving pblock relative to blueprint pblock
        public Wire partition_wire_blueprint;
        public int partition_wire_blueprint_tile_y;
        public String partition_wire_format;
        public Node partition_node;

        // blueprint metadata
        public static int blueprint_r;
        public static int blueprint_c;

        // map output direction to destination relative to source pblock
        private static final Map<String, Integer> SINK_CELL_R_OFFSET = Map.ofEntries(
            entry("east", 0),
            entry("southeast", 1),
            entry("south", 1)
        );
        private static final Map<String, Integer> SINK_CELL_C_OFFSET = Map.ofEntries(
            entry("east", 1),
            entry("southeast", 1),
            entry("south", 0)
        );

        //public StaticConnection(String direction, BufferedReader reader) throws IOException {
        public StaticConnection(BufferedReader reader) throws IOException {
            this.direction = reader.readLine();
            this.crosses_pblock = !direction.equals("south");
            this.net_format = reader.readLine();

            System.out.printf("Metadata for net %s\n", this.net_format);

            // read source cell and pin
            this.source_cell_format = reader.readLine();
            this.source_site_pin = reader.readLine();

            System.out.printf("  Source cell %s\n", this.source_cell_format);
            System.out.printf("  Source pin %s\n", this.source_site_pin);

            // read all sink cells and pins
            this.sink_cell_formats = new ArrayList<String>();
            this.sink_site_pins = new ArrayList<String>();
            String line = reader.readLine();
            while (!line.isEmpty()) {
                //String sink_cell_format = line;
                //String sink_site_pin = reader.readLine();
                //line = reader.readLine();
                System.out.printf("  Sink cell %s\n", line);
                this.sink_cell_formats.add(line);
                line = reader.readLine();
                System.out.printf("  Sink pin %s\n", line);
                this.sink_site_pins.add(line);
                line = reader.readLine();
            }

            this.partition_node = null;
            System.out.printf("  Static connection has net %s, source site pin %s, %d sink site pins\n", this.net_format, this.source_site_pin, this.sink_site_pins.size());
        }

        public void format_blueprint(Device device, int r, int c, boolean is_blueprint) {
            int sink_c = c + SINK_CELL_C_OFFSET.get(this.direction);

            this.net = String.format(this.net_format, r, c);
            this.source_cell = String.format(this.source_cell_format, r, c);
            //this.sink_cell = String.format(this.sink_cell_format,
            //    r + SINK_CELL_R_OFFSET.get(this.direction),
            //    sink_c);

            this.sink_cells = new ArrayList<String>();
            for (String format : this.sink_cell_formats) {
                this.sink_cells.add(String.format(format,
                    r + SINK_CELL_R_OFFSET.get(this.direction),
                    sink_c));
            }

            if (!is_blueprint && this.partition_wire_blueprint != null) {
                // compute site offset relative to the blueprint
                int tile_dy = (StaticConnection.blueprint_r - r) * 60;
                String wire_name = String.format(this.partition_wire_format, PE_TILE_X[sink_c], this.partition_wire_blueprint_tile_y + tile_dy); // this.partition_node_format
                System.out.printf("Transforming to net %s\n", this.net);
                System.out.printf("  Maps to wire %s with dy %d from %d\n", wire_name, tile_dy, this.partition_wire_blueprint_tile_y);

                // search for node
                this.partition_node = device.getWire(wire_name).getNode();
            }

            if (is_blueprint) {
                StaticConnection.blueprint_r = r;
                StaticConnection.blueprint_c = c;
            }
        }

        public void format(Device device, int r, int c) {
            format_blueprint(device, r, c, false);
        }

        public void computePartitionPin(int x1) {

            if (this.net_obj == null || !this.crosses_pblock) {
                return;
            }

            // get all nodes on the net
            Collection<Node> nodes = RouterHelper.getNodesOfNet(this.net_obj);

            System.out.printf("For net %s, target site X is %d\n", this.net_obj.toString(), x1+1);

            // iterate to find those on the partition boundary
            Wire partitionWire = null;
            for (Node node : nodes) {
                Wire[] wires = node.getAllWiresInNode();
                int i;
                for (i = 0; i < wires.length; ++i) {
                    partitionWire = wires[i];

                    // ensure site is in receiving pblock
                    Tile tile = partitionWire.getTile();
                    Site[] sites = tile.getSites();
                    if (sites.length == 0 || sites[0].getInstanceX() != x1+1) {
                        continue;
                    }

                    // check that the wire crosses the tile boundary
                    Pattern pattern = Pattern.compile(".*BUS(IN|OUT).*");
                    Matcher matcher = pattern.matcher(partitionWire.toString());
                    if (matcher.find()) {
                        //System.out.printf("Found match for wire %s\n", partitionWire.toString());
                        break;
                    }
                }

                if (i < wires.length) {
                    break;
                }
            }

            // save partition node
            this.partition_wire_blueprint = partitionWire;
            System.out.printf("Partition wire is %s\n", partitionWire.toString());

            // generate formatting string
            // CLEM_X52Y179/EASTBUSIN_FT0_43 => CLEM_X%dY%d/EASTBUSIN_FT0_43
            String[] tokens = partitionWire.toString().split("[XY/]");
            this.partition_wire_blueprint_tile_y = Integer.parseInt(tokens[2]);
            this.partition_wire_format = tokens[0] + "X%dY%d/" + tokens[3];

            System.out.printf("  Y is %d and format is %s\n", this.partition_wire_blueprint_tile_y, this.partition_wire_format);
        }

    }

    // route all nets leaving the specified processing element region
    private ArrayList<StaticConnection> routeBlueprintPERegion(Design design, Router router) throws Exception {

        int r = blueprint_idx / MESH_COLS;
        int c = blueprint_idx % MESH_COLS;

        // construct base pblock range
        int x0 = GUTTER_BOUNDS_SLICE_X[c][0];
        int x1 = GUTTER_BOUNDS_SLICE_X[c][1];
        int y0 = (3 - r) * 60;
        int y1 = y0 + 119;
        String base_range = getPBlockRange(x0, x1, y0, y1);

        // iterate through each net and find metadata
        ArrayList<StaticConnection> nets = new ArrayList<StaticConnection>();
        BufferedReader reader = new BufferedReader(new FileReader(inter_pe_nets_path));
        System.out.printf("Reading from %s\n", inter_pe_nets_path);
        while (true) {
            // reset pblock
            router.setRoutingPblock(new PBlock(design.getDevice(), base_range));

            // read and transform names
            StaticConnection connection = new StaticConnection(reader);
            connection.format_blueprint(design.getDevice(), r, c, true);

            // route
            routeInterPENetPins(design, router, connection);
            //nets.add(connection);
            break;
        }

        // find partition nodes for each net
        for (StaticConnection connection : nets) {
            //connection.computePartitionPin(x1);
        }

        return nets;
    }

    private Design routeInterPERegion(Design design, ArrayList<StaticConnection> static_connections, int r, int c) {

        // construct base pblock range
        int x0 = GUTTER_BOUNDS_SLICE_X[c][0];
        int x1 = GUTTER_BOUNDS_SLICE_X[c][1];
        int y0 = (3 - r) * 60;
        int y1 = y0 + 119;
        String base_range = getPBlockRange(x0, x1, y0, y1);

        System.out.printf("Routing inter-PE region %d, %d\n", r, c);

        //ArrayList<Connection> connections = new ArrayList<Connection>();
        for (StaticConnection static_connection : static_connections) {
            System.out.printf("Routing connection %s to (%d, %d)\n", static_connection.net_format, r, c);
            static_connection.format(design.getDevice(), r, c);
            design = routeInterPENetPartition(
                design,
                base_range,
                static_connection
            );
        }

        return design;
    }

    public void routePartial(CodePerfTracker t) throws Exception {

        // ========================
        // ===== Parse design =====
        // ========================

        System.out.printf("Reading checkpoint from %s\n", static_dcp_path);
        Design design = Design.readCheckpoint(static_dcp_path, t);

        // compute tile-based boundaries for gutters
        //Device device = design.getDevice();
        //for (int i = 0; i < GUTTER_BOUNDS_SLICE_X.length; ++i) {
        //    String site_name = "SLICE_X" + Integer.toString(GUTTER_BOUNDS_SLICE_X[i][0]) + "Y0";
        //    Site site = device.getSite(site_name);
        //    Tile tile = site.getTile();
        //}

        // =====================================
        // ===== Generate blueprint region =====
        // =====================================

        Router router = new Router(design);
        ArrayList<StaticConnection> connections;

        try {
            connections = routeBlueprintPERegion(design, router);
        } catch (Exception e) {
            System.out.printf("Blueprint routing for blueprint gutter failed\n");
            System.out.println(e.toString());
            throw e;
        }

        //router.getDesign().writeCheckpoint(routed_blueprint_dcp_path, t);
        //design.writeCheckpoint(routed_blueprint_dcp_path, t);

        if (router != null) return;

	    // ====================================
        // ===== For all inter-PE regions =====
        // ====================================

        int i = 0;
        for (int c = 0; c < MESH_COLS - 1; ++c) {
            for (int r = 0; r < MESH_ROWS; ++r, ++i) {
                if (i != blueprint_idx) {
                    design = routeInterPERegion(design, connections, r, c);
                    break;
                }
            }
            break;
        }

        design.writeCheckpoint(routed_dcp_path, t);

        // partition pins
        // this works after reading constraints
        //List<PartitionPin> ppins = design.getPartitionPins(targetNet);
    }

    public void matchPartpins(CodePerfTracker t) throws Exception {

        // from blueprint, map input and output partition pins

        // export partition pins

    }

    public static void main(String[] args) throws Exception {
        CodePerfTracker t = new CodePerfTracker("ComposablePAT", true);

        new ComposablePAT(args).routePartial(t);
    }

}
