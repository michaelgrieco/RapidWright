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
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.router.RouteThruHelper;
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
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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

    public static void composablePAT(String[] args) throws Exception {

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

    public static void moduleInst(String[] args) {

        if (args.length < 4) {
            System.out.println("USAGE: <dcp_path> <blueprint_pblock> <blueprint_range> <blueprint_idx>");
            return;
        }

        String blueprint_pblock = args[1];
        String blueprint_pblock_range = args[2];
        String blueprint_dcp_path = args[0] + "/" + blueprint_pblock + "_processing_element_identity_route_design.dcp";
        String blueprint_edif_path = args[0] + "/" + blueprint_pblock + "_processing_element_identity_route_design.edif";
        String transformed_dcp_path = args[0] + "/" + blueprint_pblock + "_processing_element_identity_route_design.test.dcp";
        int blueprint_idx = Integer.parseInt(args[3]);
        if (blueprint_idx >= NUM_PES) {
            System.out.printf("ERROR: blueprint index, %d, is too large (>= %d)\n", blueprint_idx, NUM_PES);
        }

        // original design
        Design ogDesign = Design.readCheckpoint(blueprint_dcp_path, blueprint_edif_path);
        Module mod = new Module(ogDesign);
        PBlock pblock = new PBlock(ogDesign.getDevice(), blueprint_pblock_range);
        EDIFNetlist netlist = ogDesign.getNetlist();

        for (EDIFPort p : netlist.getTopCell().getPorts()) {
            String name = p.toString();
            System.out.printf("EDIF port %s\n", name);
            for (int i = 0; i < p.getWidth(); i++) {
                EDIFNet en = p.getInternalNet(i);
                name = en.toString();
                Net n = ogDesign.getNet(name);
                if (n != null) {
                    System.out.printf(" => Design net %s\n", n.toString());
                }
                List<PartitionPin> ppins = ogDesign.getPartitionPins(n);
                for (PartitionPin ppin : ppins) {
                    System.out.printf("   => Partition pin %s\n", ppin.toString());
                }
            }
        }

/*
        // new design
        Design design = new Design("top", ogDesign.getDevice().toString());

        design.getNetlist().migrateCellAndSubCells(mod.getNetlist().getTopCell());
        ModuleInst inst = design.createModuleInst("inst", mod);
        inst.place(mod.getAnchor());

        design.writeCheckpoint(transformed_dcp_path);
*/

    }

    public static void main(String[] args) throws Exception {
        //relocateToolsExample(args);
        composablePAT(args);
        //moduleInst(args);
    }

}
