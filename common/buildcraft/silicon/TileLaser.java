/**
 * Copyright (c) 2011-2014, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.silicon;

import java.util.LinkedList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.ForgeDirection;
import buildcraft.api.core.NetworkData;
import buildcraft.api.core.Position;
import buildcraft.api.core.SafeTimeTracker;
import buildcraft.api.power.ILaserTarget;
import buildcraft.api.power.ILaserTargetBlock;
import buildcraft.api.tiles.IControllable;
import buildcraft.api.tiles.IHasWork;
import buildcraft.core.Box;
import buildcraft.core.EntityLaser;
import buildcraft.core.LaserData;
import buildcraft.core.RFBattery;
import buildcraft.core.TileBuildCraft;

public class TileLaser extends TileBuildCraft implements IHasWork, IControllable {

	private static final float LASER_OFFSET = 2.0F / 16.0F;
	private static final short POWER_AVERAGING = 100;

	@NetworkData
	public LaserData laser = new LaserData();
	
	private final SafeTimeTracker laserTickTracker = new SafeTimeTracker(10);
	private final SafeTimeTracker searchTracker = new SafeTimeTracker(100, 100);
	private final SafeTimeTracker networkTracker = new SafeTimeTracker(20, 3);
	private ILaserTarget laserTarget;
	private IControllable.Mode lastMode = IControllable.Mode.Unknown;
	private int powerIndex = 0;

	@NetworkData
	private short powerAverage = 0;
	private final short[] power = new short[POWER_AVERAGING];

	public TileLaser() {
		super();
		this.setBattery(new RFBattery(10000, 250, 0));
	}
	@Override
	public void initialize() {
		super.initialize();

		if (laser == null) {
			laser = new LaserData();
		}
		
		laser.isVisible = false;
		laser.head = new Position(xCoord, yCoord, zCoord);
		laser.tail = new Position(xCoord, yCoord, zCoord);
	}

	@Override
	public void updateEntity() {
		super.updateEntity();

		laser.iterateTexture();

		if (!!worldObj.isRemote) {
			return;
		}

		// If a gate disabled us, remove laser and do nothing.
		if (lastMode == IControllable.Mode.Off) {
			removeLaser();
			return;
		}

		// Check for any available tables at a regular basis
		if (canFindTable()) {
			findTable();
		}

		// If we still don't have a valid table or the existing has
		// become invalid, we disable the laser and do nothing.
		if (!isValidTable()) {
			removeLaser();
			return;
		}

		// Disable the laser and do nothing if no energy is available.
		if (getBattery().getEnergyStored() == 0) {
			removeLaser();
			return;
		}

		// We have a table and can work, so we create a laser if
		// necessary.
		laser.isVisible = true;

		// We have a laser and may update it
		if (laser != null && canUpdateLaser()) {
			updateLaser();
		}

		// Consume power and transfer it to the table.
		int localPower = getBattery().useEnergy(0, getMaxPowerSent(), false);
		laserTarget.receiveLaserEnergy(localPower);

		if (laser != null) {
			pushPower(localPower);
		}

		onPowerSent(localPower);

		sendNetworkUpdate();
	}

	protected int getMaxPowerSent() {
		return 40;
	}

	protected void onPowerSent(int power) {
	}

	protected boolean canFindTable() {
		return searchTracker.markTimeIfDelay(worldObj);
	}

	protected boolean canUpdateLaser() {
		return laserTickTracker.markTimeIfDelay(worldObj);
	}

	protected boolean isValidTable() {

		if (laserTarget == null || laserTarget.isInvalidTarget() || !laserTarget.requiresLaserEnergy()) {
			return false;
		}

		return true;
	}

	protected void findTable() {
		int meta = getBlockMetadata();

		int minX = xCoord - 5;
		int minY = yCoord - 5;
		int minZ = zCoord - 5;
		int maxX = xCoord + 5;
		int maxY = yCoord + 5;
		int maxZ = zCoord + 5;

		switch (ForgeDirection.getOrientation(meta)) {
			case WEST:
				maxX = xCoord;
				break;
			case EAST:
				minX = xCoord;
				break;
			case DOWN:
				maxY = yCoord;
				break;
			case UP:
				minY = yCoord;
				break;
			case NORTH:
				maxZ = zCoord;
				break;
			default:
			case SOUTH:
				minZ = zCoord;
				break;
		}

		List<ILaserTarget> targets = new LinkedList<ILaserTarget>();

		for (int x = minX; x <= maxX; ++x) {
			for (int y = minY; y <= maxY; ++y) {
				for (int z = minZ; z <= maxZ; ++z) {
					if (worldObj.getBlock(x, y, z) instanceof ILaserTargetBlock) {
						TileEntity tile = worldObj.getTileEntity(x, y, z);
						
						if (tile instanceof ILaserTarget) {
							ILaserTarget table = (ILaserTarget) tile;
							
							if (table.requiresLaserEnergy()) {
								targets.add(table);
							}
						}
					}
				}
			}
		}

		if (targets.isEmpty()) {
			return;
		}

		laserTarget = targets.get(worldObj.rand.nextInt(targets.size()));
	}

	protected void updateLaser() {

		int meta = getBlockMetadata();
		double px = 0, py = 0, pz = 0;

		switch (ForgeDirection.getOrientation(meta)) {

			case WEST:
				px = -LASER_OFFSET;
				break;
			case EAST:
				px = LASER_OFFSET;
				break;
			case DOWN:
				py = -LASER_OFFSET;
				break;
			case UP:
				py = LASER_OFFSET;
				break;
			case NORTH:
				pz = -LASER_OFFSET;
				break;
			case SOUTH:
			default:
				pz = LASER_OFFSET;
				break;
		}

		Position head = new Position(xCoord + 0.5 + px, yCoord + 0.5 + py, zCoord + 0.5 + pz);
		Position tail = new Position(laserTarget.getXCoord() + 0.475 + (worldObj.rand.nextFloat() - 0.5) / 5F, laserTarget.getYCoord() + 9F / 16F,
				laserTarget.getZCoord() + 0.475 + (worldObj.rand.nextFloat() - 0.5) / 5F);

		laser.head = head;
		laser.tail = tail;

		if (!laser.isVisible) {
			laser.isVisible = true;
		}
	}

	protected void removeLaser() {
		if (laser.isVisible) {
			laser.isVisible = false;
			// force sending the network update even if the network tracker
			// refuses.
			super.sendNetworkUpdate();
		}
	}

	@Override
	public void sendNetworkUpdate() {
		if (networkTracker.markTimeIfDelay(worldObj)) {
			super.sendNetworkUpdate();
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound nbttagcompound) {
		super.readFromNBT(nbttagcompound);
	}

	@Override
	public void writeToNBT(NBTTagCompound nbttagcompound) {
		super.writeToNBT(nbttagcompound);
	}

	@Override
	public void invalidate() {
		super.invalidate();
		removeLaser();
	}

	@Override
	public boolean hasWork() {
		return isValidTable();
	}

	private void pushPower(int received) {
		powerAverage -= power[powerIndex];
		powerAverage += received;
		power[powerIndex] = (short) received;
		powerIndex++;

		if (powerIndex == power.length) {
			powerIndex = 0;
		}
	}

	public ResourceLocation getTexture() {
		double avg = powerAverage / POWER_AVERAGING;

		if (avg <= 1.0) {
			return EntityLaser.LASER_TEXTURES[0];
		} else if (avg <= 2.0) {
			return EntityLaser.LASER_TEXTURES[1];
		} else if (avg <= 3.0) {
			return EntityLaser.LASER_TEXTURES[2];
		} else {
			return EntityLaser.LASER_TEXTURES[3];
		}
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return new Box(this).extendToEncompass(laser.tail).getBoundingBox();
	}
	
	@Override
	public Mode getControlMode() {
		return this.lastMode;
	}
	
	@Override
	public void setControlMode(Mode mode) {
		this.lastMode = mode;
	}
	
	@Override
	public boolean acceptsControlMode(Mode mode) {
		return mode == IControllable.Mode.On ||
				mode == IControllable.Mode.Off;
	}
}
