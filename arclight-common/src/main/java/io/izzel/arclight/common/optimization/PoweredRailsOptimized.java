package io.izzel.arclight.common.optimization;

import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * 动力铁轨更新优化（移植自 Fluorite / moe.kotori.fluorite.optimize.PoweredRailsOptimized；
 * 1.20.1 Luminara 已在生产运行，本文件为 1.21.1 移植版）。
 *
 * 原版 PoweredRailBlock.updateState 在每次红石/邻居变化时会对整条铁轨线路做开销较大的
 * 递归输电检测与邻居通知。这里用一个 HashMap 记忆已检查过的方块、限制输电传播距离
 * （RAIL_POWER_LIMIT），并只在线路两端做形状/邻居更新，大幅削减大规模动力铁轨网络的更新开销。
 *
 * 与 1.20.1 版的差异：protected 的 PoweredRailBlock.findPoweredRailSignal 不再走
 * accesstransformer.cfg（1.21.1 树无 MC 类 AT），改经 {@link PoweredRailBlockBridge}
 * （由 core PoweredRailBlockMixin @Shadow 实现）调用；
 * Block.updateOrDestroy 在 1.21.1 已是 public，无需处理。
 */
public class PoweredRailsOptimized {

    private static final Direction[] EAST_WEST_DIR = new Direction[]{Direction.WEST, Direction.EAST};
    private static final Direction[] NORTH_SOUTH_DIR = new Direction[]{Direction.SOUTH, Direction.NORTH};
    private static final int UPDATE_FORCE_PLACE = 82;
    public static int RAIL_POWER_LIMIT = 8;

    private static boolean findPoweredRailSignal(PoweredRailBlock self, Level level, BlockPos pos, BlockState state, boolean travelDirection, int depth) {
        return ((PoweredRailBlockBridge) self).luminara$findPoweredRailSignal(level, pos, state, travelDirection, depth);
    }

    public static void giveShapeUpdate(Level level, BlockState state, BlockPos pos, BlockPos fromPos, Direction direction) {
        BlockState oldState = level.getBlockState(pos);
        Block.updateOrDestroy(oldState, oldState.updateShape(direction.getOpposite(), state, level, pos, fromPos), level, pos, 2, 0);
    }

    public static void setRailPowerLimit(int powerLimit) {
        RAIL_POWER_LIMIT = powerLimit;
    }

    public static void customUpdateState(PoweredRailBlock self, BlockState state, Level level, BlockPos pos) {
        boolean shouldBePowered = level.hasNeighborSignal(pos)
                || findPoweredRailSignal(self, level, pos, state, true, 0)
                || findPoweredRailSignal(self, level, pos, state, false, 0);
        if (shouldBePowered != state.getValue(PoweredRailBlock.POWERED)) {
            RailShape railShape = state.getValue(PoweredRailBlock.SHAPE);
            if (railShape == RailShape.NORTH_SOUTH || railShape == RailShape.EAST_WEST) {
                level.setBlock(pos, state.setValue(PoweredRailBlock.POWERED, shouldBePowered), 3);
                level.updateNeighborsAtExceptFromFacing(pos.relative(Direction.DOWN), self, Direction.UP);
                level.updateNeighborsAtExceptFromFacing(pos.relative(Direction.UP), self, Direction.DOWN);
            } else if (shouldBePowered) {
                powerLane(self, level, pos, state, railShape);
            } else {
                dePowerLane(self, level, pos, state, railShape);
            }
        }
    }

    public static boolean findPoweredRailSignalFaster(PoweredRailBlock self, Level world, BlockPos pos, boolean bl, int distance, RailShape shape, HashMap<BlockPos, Boolean> checkedPos) {
        BlockState blockState = world.getBlockState(pos);
        boolean speedCheck = checkedPos.containsKey(pos) && checkedPos.get(pos) != false;
        if (speedCheck) {
            return world.hasNeighborSignal(pos) || findPoweredRailSignalFaster(self, world, pos, blockState, bl, distance + 1, checkedPos);
        }
        if (blockState.is(self)) {
            RailShape railShape = blockState.getValue(PoweredRailBlock.SHAPE);
            if (shape == RailShape.EAST_WEST && (railShape == RailShape.NORTH_SOUTH || railShape == RailShape.ASCENDING_NORTH || railShape == RailShape.ASCENDING_SOUTH)
                    || shape == RailShape.NORTH_SOUTH && (railShape == RailShape.EAST_WEST || railShape == RailShape.ASCENDING_EAST || railShape == RailShape.ASCENDING_WEST)) {
                return false;
            }
            if (blockState.getValue(PoweredRailBlock.POWERED)) {
                return world.hasNeighborSignal(pos) || findPoweredRailSignalFaster(self, world, pos, blockState, bl, distance + 1, checkedPos);
            }
            return false;
        }
        return false;
    }

    public static boolean findPoweredRailSignalFaster(PoweredRailBlock self, Level level, BlockPos pos, BlockState state, boolean bl, int distance, HashMap<BlockPos, Boolean> checkedPos) {
        if (distance >= RAIL_POWER_LIMIT - 1) {
            return false;
        }
        int i = pos.getX();
        int j = pos.getY();
        int k = pos.getZ();
        boolean bl2 = true;
        RailShape railShape = state.getValue(PoweredRailBlock.SHAPE);
        switch (railShape.ordinal()) {
            case 0: {
                if (bl) {
                    ++k;
                } else {
                    --k;
                }
                break;
            }
            case 1: {
                if (bl) {
                    --i;
                } else {
                    ++i;
                }
                break;
            }
            case 2: {
                if (bl) {
                    --i;
                } else {
                    ++i;
                    ++j;
                    bl2 = false;
                }
                railShape = RailShape.EAST_WEST;
                break;
            }
            case 3: {
                if (bl) {
                    --i;
                    ++j;
                    bl2 = false;
                } else {
                    ++i;
                }
                railShape = RailShape.EAST_WEST;
                break;
            }
            case 4: {
                if (bl) {
                    ++k;
                } else {
                    --k;
                    ++j;
                    bl2 = false;
                }
                railShape = RailShape.NORTH_SOUTH;
                break;
            }
            case 5: {
                if (bl) {
                    ++k;
                    ++j;
                    bl2 = false;
                } else {
                    --k;
                }
                railShape = RailShape.NORTH_SOUTH;
            }
        }
        return findPoweredRailSignalFaster(self, level, new BlockPos(i, j, k), bl, distance, railShape, checkedPos)
                || bl2 && findPoweredRailSignalFaster(self, level, new BlockPos(i, j - 1, k), bl, distance, railShape, checkedPos);
    }

    public static void powerLane(PoweredRailBlock self, Level world, BlockPos pos, BlockState mainState, RailShape railShape) {
        world.setBlock(pos, mainState.setValue(PoweredRailBlock.POWERED, true), UPDATE_FORCE_PLACE);
        HashMap<BlockPos, Boolean> checkedPos = new HashMap<>();
        checkedPos.put(pos, true);
        int[] count = new int[2];
        if (railShape == RailShape.NORTH_SOUTH) {
            for (int i = 0; i < NORTH_SOUTH_DIR.length; ++i) {
                setRailPositionsPower(self, world, pos, checkedPos, count, i, NORTH_SOUTH_DIR[i]);
            }
            updateRails(self, false, world, pos, mainState, count);
        } else if (railShape == RailShape.EAST_WEST) {
            for (int i = 0; i < EAST_WEST_DIR.length; ++i) {
                setRailPositionsPower(self, world, pos, checkedPos, count, i, EAST_WEST_DIR[i]);
            }
            updateRails(self, true, world, pos, mainState, count);
        }
    }

    public static void dePowerLane(PoweredRailBlock self, Level world, BlockPos pos, BlockState mainState, RailShape railShape) {
        world.setBlock(pos, mainState.setValue(PoweredRailBlock.POWERED, false), UPDATE_FORCE_PLACE);
        int[] count = new int[2];
        if (railShape == RailShape.NORTH_SOUTH) {
            for (int i = 0; i < NORTH_SOUTH_DIR.length; ++i) {
                setRailPositionsDePower(self, world, pos, count, i, NORTH_SOUTH_DIR[i]);
            }
            updateRails(self, false, world, pos, mainState, count);
        } else if (railShape == RailShape.EAST_WEST) {
            for (int i = 0; i < EAST_WEST_DIR.length; ++i) {
                setRailPositionsDePower(self, world, pos, count, i, EAST_WEST_DIR[i]);
            }
            updateRails(self, true, world, pos, mainState, count);
        }
    }

    private static void setRailPositionsPower(PoweredRailBlock self, Level world, BlockPos pos, HashMap<BlockPos, Boolean> checkedPos, int[] count, int i, Direction dir) {
        for (int z = 1; z < RAIL_POWER_LIMIT; ++z) {
            BlockPos newPos = pos.relative(dir, z);
            BlockState state = world.getBlockState(newPos);
            if (checkedPos.containsKey(newPos)) {
                if (!checkedPos.get(newPos)) break;
                count[i] = count[i] + 1;
                continue;
            }
            if (!state.is(self)
                    || state.getValue(PoweredRailBlock.POWERED)
                    || !world.hasNeighborSignal(newPos)
                        && !findPoweredRailSignalFaster(self, world, newPos, state, true, 0, checkedPos)
                        && !findPoweredRailSignalFaster(self, world, newPos, state, false, 0, checkedPos)) {
                checkedPos.put(newPos, false);
                break;
            }
            checkedPos.put(newPos, true);
            world.setBlock(newPos, state.setValue(PoweredRailBlock.POWERED, true), UPDATE_FORCE_PLACE);
            count[i] = count[i] + 1;
        }
    }

    private static void setRailPositionsDePower(PoweredRailBlock self, Level world, BlockPos pos, int[] count, int i, Direction dir) {
        BlockPos newPos;
        BlockState state;
        for (int z = 1; z < RAIL_POWER_LIMIT && (state = world.getBlockState(newPos = pos.relative(dir, z))).is(self)
                && state.getValue(PoweredRailBlock.POWERED)
                && !world.hasNeighborSignal(newPos)
                && !findPoweredRailSignal(self, world, newPos, state, true, 0)
                && !findPoweredRailSignal(self, world, newPos, state, false, 0); ++z) {
            world.setBlock(newPos, state.setValue(PoweredRailBlock.POWERED, false), UPDATE_FORCE_PLACE);
            count[i] = count[i] + 1;
        }
    }

    private static void shapeUpdateEnd(PoweredRailBlock self, Level world, BlockPos pos, BlockState mainState, int endPos, Direction direction, int currentPos, BlockPos blockPos) {
        if (currentPos == endPos) {
            BlockPos newPos = pos.relative(direction, currentPos + 1);
            giveShapeUpdate(world, mainState, newPos, pos, direction);
            BlockState state = world.getBlockState(blockPos);
            RailShape shape = state.getValue(PoweredRailBlock.SHAPE);
            if (state.is(self) && (shape == RailShape.NORTH_SOUTH || shape == RailShape.EAST_WEST)) {
                giveShapeUpdate(world, mainState, newPos.relative(Direction.UP), pos, direction);
            }
        }
    }

    private static void neighborUpdateEnd(PoweredRailBlock self, Level world, BlockPos pos, int endPos, Direction direction, Block block, int currentPos, BlockPos blockPos) {
        if (currentPos == endPos) {
            BlockPos newPos = pos.relative(direction, currentPos + 1);
            world.neighborChanged(newPos, block, pos);
            BlockState state = world.getBlockState(blockPos);
            RailShape shape = state.getValue(PoweredRailBlock.SHAPE);
            if (state.is(self) && (shape == RailShape.NORTH_SOUTH || shape == RailShape.EAST_WEST)) {
                world.neighborChanged(newPos.relative(Direction.UP), block, blockPos);
            }
        }
    }

    private static void updateRailsSectionEastWestShape(PoweredRailBlock self, Level world, BlockPos pos, int c, BlockState mainState, Direction dir, int[] count, int countAmt) {
        BlockPos pos1 = pos.relative(dir, c);
        if (c == 0 && count[1] == 0) {
            giveShapeUpdate(world, mainState, pos1.relative(dir.getOpposite()), pos, dir.getOpposite());
        }
        shapeUpdateEnd(self, world, pos, mainState, countAmt, dir, c, pos1);
        giveShapeUpdate(world, mainState, pos1.relative(Direction.DOWN), pos, Direction.DOWN);
        giveShapeUpdate(world, mainState, pos1.relative(Direction.UP), pos, Direction.UP);
        giveShapeUpdate(world, mainState, pos1.relative(Direction.NORTH), pos, Direction.NORTH);
        giveShapeUpdate(world, mainState, pos1.relative(Direction.SOUTH), pos, Direction.SOUTH);
    }

    private static void updateRailsSectionNorthSouthShape(PoweredRailBlock self, Level world, BlockPos pos, int c, BlockState mainState, Direction dir, int[] count, int countAmt) {
        BlockPos pos1 = pos.relative(dir, c);
        giveShapeUpdate(world, mainState, pos1.relative(Direction.WEST), pos, Direction.WEST);
        giveShapeUpdate(world, mainState, pos1.relative(Direction.EAST), pos, Direction.EAST);
        giveShapeUpdate(world, mainState, pos1.relative(Direction.DOWN), pos, Direction.DOWN);
        giveShapeUpdate(world, mainState, pos1.relative(Direction.UP), pos, Direction.UP);
        shapeUpdateEnd(self, world, pos, mainState, countAmt, dir, c, pos1);
        if (c == 0 && count[1] == 0) {
            giveShapeUpdate(world, mainState, pos1.relative(dir.getOpposite()), pos, dir.getOpposite());
        }
    }

    private static void updateRails(PoweredRailBlock self, boolean eastWest, Level world, BlockPos pos, BlockState mainState, int[] count) {
        if (eastWest) {
            for (int i = 0; i < EAST_WEST_DIR.length; ++i) {
                int countAmt = count[i];
                if (i == 1 && countAmt == 0) continue;
                Direction dir = EAST_WEST_DIR[i];
                Block block = mainState.getBlock();
                for (int c = countAmt; c >= i; --c) {
                    BlockPos p = pos.relative(dir, c);
                    if (c == 0 && count[1] == 0) {
                        world.neighborChanged(p.relative(dir.getOpposite()), block, pos);
                    }
                    neighborUpdateEnd(self, world, pos, countAmt, dir, block, c, p);
                    world.neighborChanged(p.relative(Direction.DOWN), block, pos);
                    world.neighborChanged(p.relative(Direction.UP), block, pos);
                    world.neighborChanged(p.relative(Direction.NORTH), block, pos);
                    world.neighborChanged(p.relative(Direction.SOUTH), block, pos);
                    BlockPos pos2 = pos.relative(dir, c).relative(Direction.DOWN);
                    world.neighborChanged(pos2.relative(Direction.DOWN), block, pos);
                    world.neighborChanged(pos2.relative(Direction.NORTH), block, pos);
                    world.neighborChanged(pos2.relative(Direction.SOUTH), block, pos);
                    if (c == countAmt) {
                        world.neighborChanged(pos.relative(dir, c + 1).relative(Direction.DOWN), block, pos);
                    }
                    if (c != 0 || count[1] != 0) continue;
                    world.neighborChanged(p.relative(dir.getOpposite()).relative(Direction.DOWN), block, pos);
                }
                for (int c = countAmt; c >= i; --c) {
                    updateRailsSectionEastWestShape(self, world, pos, c, mainState, dir, count, countAmt);
                }
            }
        } else {
            for (int i = 0; i < NORTH_SOUTH_DIR.length; ++i) {
                int countAmt = count[i];
                if (i == 1 && countAmt == 0) continue;
                Direction dir = NORTH_SOUTH_DIR[i];
                Block block = mainState.getBlock();
                for (int c = countAmt; c >= i; --c) {
                    BlockPos p = pos.relative(dir, c);
                    world.neighborChanged(p.relative(Direction.WEST), block, pos);
                    world.neighborChanged(p.relative(Direction.EAST), block, pos);
                    world.neighborChanged(p.relative(Direction.DOWN), block, pos);
                    world.neighborChanged(p.relative(Direction.UP), block, pos);
                    neighborUpdateEnd(self, world, pos, countAmt, dir, block, c, p);
                    if (c == 0 && count[1] == 0) {
                        world.neighborChanged(p.relative(dir.getOpposite()), block, pos);
                    }
                    BlockPos pos2 = pos.relative(dir, c).relative(Direction.DOWN);
                    world.neighborChanged(pos2.relative(Direction.WEST), block, pos);
                    world.neighborChanged(pos2.relative(Direction.EAST), block, pos);
                    world.neighborChanged(pos2.relative(Direction.DOWN), block, pos);
                    if (c == countAmt) {
                        world.neighborChanged(pos.relative(dir, c + 1).relative(Direction.DOWN), block, pos);
                    }
                    if (c != 0 || count[1] != 0) continue;
                    world.neighborChanged(p.relative(dir.getOpposite()).relative(Direction.DOWN), block, pos);
                }
                for (int c = countAmt; c >= i; --c) {
                    updateRailsSectionNorthSouthShape(self, world, pos, c, mainState, dir, count, countAmt);
                }
            }
        }
    }
}
