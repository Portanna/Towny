package com.palmergames.bukkit.towny.listeners;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Coord;
import com.palmergames.bukkit.towny.object.PlayerCache;
import com.palmergames.bukkit.towny.object.PlayerCache.TownBlockStatus;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.object.TownyUniverse;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.bukkit.towny.regen.TownyRegenAPI;
import com.palmergames.bukkit.towny.regen.block.BlockLocation;
import com.palmergames.bukkit.towny.tasks.ProtectionRegenTask;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import com.palmergames.bukkit.towny.war.flagwar.TownyWar;
import com.palmergames.bukkit.towny.war.flagwar.TownyWarConfig;
import com.palmergames.bukkit.util.BukkitTools;

public class TownyBlockListener implements Listener {

	private final Towny plugin;

	public TownyBlockListener(Towny instance) {

		plugin = instance;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {

		if (plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		Player player = event.getPlayer();
		Block block = event.getBlock();

		//Get build permissions (updates cache if none exist)
		boolean bDestroy = PlayerCacheUtil.getCachePermission(player, block.getLocation(), BukkitTools.getTypeId(block), BukkitTools.getData(block), TownyPermission.ActionType.DESTROY);
		
		// Allow destroy if we are permitted
		if (bDestroy)
			return;

		/*
		 * Fetch the players cache
		 */
		PlayerCache cache = plugin.getCache(player);

		/*
		 * Allow destroy in a WarZone (FlagWar) if it's an editable material.
		 */
		if (cache.getStatus() == TownBlockStatus.WARZONE) {
			if (!TownyWarConfig.isEditableMaterialInWarZone(block.getType())) {
				event.setCancelled(true);
				TownyMessaging.sendErrorMsg(player, String.format(TownySettings.getLangString("msg_err_warzone_cannot_edit_material"), "destroy", block.getType().toString().toLowerCase()));
			}
			return;
		}

		/*
		 * Queue a protectionRegenTask if we have delayed regeneration set
		 */
		long delay = TownySettings.getRegenDelay();
		if (delay > 0) {
			if (!TownyRegenAPI.isPlaceholder(block)) {
				if (!TownyRegenAPI.hasProtectionRegenTask(new BlockLocation(block.getLocation()))) {
					ProtectionRegenTask task = new ProtectionRegenTask(plugin, block, true);
					task.setTaskId(plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, task, 20 * delay));
					TownyRegenAPI.addProtectionRegenTask(task);
				}
			} else {
				TownyRegenAPI.removePlaceholder(block);
				BukkitTools.setTypeId(block, 0, false);
			}
		} else {
			event.setCancelled(true);
		}

		/* 
		 * display any error recorded for this plot
		 */
		if ((cache.hasBlockErrMsg()) && (event.isCancelled()))
			TownyMessaging.sendErrorMsg(player, cache.getBlockErrMsg());

	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBlockPlace(BlockPlaceEvent event) {

		if (plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		Player player = event.getPlayer();
		Block block = event.getBlock();
		WorldCoord worldCoord;
		
		try {
			TownyWorld world = TownyUniverse.getDataSource().getWorld(block.getWorld().getName());
			worldCoord = new WorldCoord(world.getName(), Coord.parseCoord(block));

			//Get build permissions (updates if none exist)
			boolean bBuild = PlayerCacheUtil.getCachePermission(player, block.getLocation(), BukkitTools.getTypeId(block), BukkitTools.getData(block), TownyPermission.ActionType.BUILD);

			// Allow build if we are permitted
			if (bBuild)
				return;
			
			/*
			 * Fetch the players cache
			 */
			PlayerCache cache = plugin.getCache(player);
			TownBlockStatus status = cache.getStatus();

			/*
			 * Flag war
			 */
			if (((status == TownBlockStatus.ENEMY) && TownyWarConfig.isAllowingAttacks()) && (event.getBlock().getType() == TownyWarConfig.getFlagBaseMaterial())) {

				try {
					if (TownyWar.callAttackCellEvent(plugin, player, block, worldCoord))
						return;
				} catch (TownyException e) {
					TownyMessaging.sendErrorMsg(player, e.getMessage());
				}

				event.setBuild(false);
				event.setCancelled(true);

			} else if (status == TownBlockStatus.WARZONE) {
				if (!TownyWarConfig.isEditableMaterialInWarZone(block.getType())) {
					event.setBuild(false);
					event.setCancelled(true);
					TownyMessaging.sendErrorMsg(player, String.format(TownySettings.getLangString("msg_err_warzone_cannot_edit_material"), "build", block.getType().toString().toLowerCase()));
				}
				return;
			} else {
				event.setBuild(false);
				event.setCancelled(true);
			}

			/* 
			 * display any error recorded for this plot
			 */
			if ((cache.hasBlockErrMsg()) && (event.isCancelled()))
				TownyMessaging.sendErrorMsg(player, cache.getBlockErrMsg());

		} catch (NotRegisteredException e1) {
			TownyMessaging.sendErrorMsg(player, TownySettings.getLangString("msg_err_not_configured"));
			event.setCancelled(true);
		}

	}

	// prevent blocks igniting if within a protected town area when fire spread is set to off.
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBlockBurn(BlockBurnEvent event) {

		if (plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		if (onBurn(event.getBlock()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onBlockIgnite(BlockIgniteEvent event) {

		if (event.isCancelled() || plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		if (onBurn(event.getBlock()))
			event.setCancelled(true);

	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBlockPistonRetract(BlockPistonRetractEvent event) {

		if (plugin.isError()) {
			event.setCancelled(true);
			return;
		}
		
		List<Block> blocks = event.getBlocks();

		if (!blocks.isEmpty()) {
			//check each block to see if it's going to pass a plot boundary
			for (Block block : blocks) {
				if (testBlockMove(block, event.getDirection()))
					event.setCancelled(true);
			}
		}

//      OLD PISTON RETRACTION CODE		
//		//fetch the piston base
//		Block block = event.getBlock();
//
//		if (block.getType() != Material.PISTON_MOVING_PIECE) 
//			return;
//
//		//Get the block attached to the PISTON_EXTENSION of the PISTON_STICKY_BASE
//		block = block.getRelative(event.getDirection().getOppositeFace()).getRelative(event.getDirection().getOppositeFace());
//
//		if ((block.getType() != Material.AIR) && (!block.isLiquid())) {
//
//			//check the block to see if it's going to pass a plot boundary
//			if (testBlockMove(block, event.getDirection()))// .getOppositeFace()))
//				event.setCancelled(true);
//		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBlockPistonExtend(BlockPistonExtendEvent event) {

		if (plugin.isError()) {
			event.setCancelled(true);
			return;
		}

		List<Block> blocks = event.getBlocks();

		if (!blocks.isEmpty()) {
			//check each block to see if it's going to pass a plot boundary
			for (Block block : blocks) {
				if (testBlockMove(block, event.getDirection()))
					event.setCancelled(true);
			}
		}
	}

	private boolean testBlockMove(Block block, BlockFace direction) {

		Block blockTo = block.getRelative(direction);
		Location loc = block.getLocation();
		Location locTo = blockTo.getLocation();
		Coord coord = Coord.parseCoord(loc);
		Coord coordTo = Coord.parseCoord(locTo);

		TownyWorld townyWorld = null;
		TownBlock CurrentTownBlock = null, destinationTownBlock = null;

		try {
			townyWorld = TownyUniverse.getDataSource().getWorld(loc.getWorld().getName());
			CurrentTownBlock = townyWorld.getTownBlock(coord);
		} catch (NotRegisteredException e) {
			//System.out.print("Failed to fetch TownBlock");
		}

		try {
			destinationTownBlock = townyWorld.getTownBlock(coordTo);
		} catch (NotRegisteredException e1) {
			//System.out.print("Failed to fetch TownBlockTo");
		}

		if (CurrentTownBlock != destinationTownBlock) {

			// Cancel if either is not null, but other is (wild to town).
			if (((CurrentTownBlock == null) && (destinationTownBlock != null)) || ((CurrentTownBlock != null) && (destinationTownBlock == null))) {
				//event.setCancelled(true);
				return true;
			}

			// If both blocks are owned by the town.
			if (!CurrentTownBlock.hasResident() && !destinationTownBlock.hasResident())
				return false;

			try {
				if ((!CurrentTownBlock.hasResident() && destinationTownBlock.hasResident()) || (CurrentTownBlock.hasResident() && !destinationTownBlock.hasResident()) || (CurrentTownBlock.getResident() != destinationTownBlock.getResident())

				|| (CurrentTownBlock.getPlotPrice() != -1) || (destinationTownBlock.getPlotPrice() != -1)) {
					return true;
				}
			} catch (NotRegisteredException e) {
				// Failed to fetch a resident
				return true;
			}
		}

		return false;
	}

	private boolean onBurn(Block block) {

		Location loc = block.getLocation();
		Coord coord = Coord.parseCoord(loc);
		TownyWorld townyWorld;

		try {
			townyWorld = TownyUniverse.getDataSource().getWorld(loc.getWorld().getName());

			if (!townyWorld.isUsingTowny())
				return false;

			try {

				if (townyWorld.isWarZone(coord)) {
					if (TownyWarConfig.isAllowingFireInWarZone()) {
						return false;
					} else {
						TownyMessaging.sendDebugMsg("onBlockIgnite: Canceled " + block.getType().name() + " from igniting within " + coord.toString() + ".");
						return true;
					}
				}

				TownBlock townBlock = townyWorld.getTownBlock(coord);
				if ((block.getRelative(BlockFace.DOWN).getType() != Material.OBSIDIAN) && ((!townBlock.getTown().isFire() && !townyWorld.isForceFire() && !townBlock.getPermissions().fire) || (TownyUniverse.isWarTime() && TownySettings.isAllowWarBlockGriefing() && !townBlock.getTown().hasNation()))) {
					TownyMessaging.sendDebugMsg("onBlockIgnite: Canceled " + block.getType().name() + " from igniting within " + coord.toString() + ".");
					return true;
				}
			} catch (TownyException x) {
				// Not a town so check the world setting for fire
				if (!townyWorld.isFire()) {
					TownyMessaging.sendDebugMsg("onBlockIgnite: Canceled " + block.getType().name() + " from igniting within " + coord.toString() + ".");
					return true;
				}
			}

		} catch (NotRegisteredException e) {
			// Failed to fetch the world
		}

		return false;
	}
	
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onCreateExplosion(BlockExplodeEvent event) {
		if (plugin.isError()) {
			event.setCancelled(true);
			return;
		}
		
		TownyWorld townyWorld = null;
		List<Block> blocks = event.blockList();
		int count = 0;
		
		try {
			townyWorld = TownyUniverse.getDataSource().getWorld(event.getBlock().getLocation().getWorld().getName());
		} catch (NotRegisteredException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		for (Block block : blocks) {
			count++;
			
			if (!locationCanExplode(townyWorld, block.getLocation())) {
				event.setCancelled(true);
				return;
			}
			
			if (TownyUniverse.isWilderness(block)) {
				if (townyWorld.isUsingTowny()) {
					if (townyWorld.isExpl()) {
						if (townyWorld.isUsingPlotManagementWildRevert()) {
							//TownyMessaging.sendDebugMsg("onCreateExplosion: Testing block: " + entity.getType().getEntityClass().getSimpleName().toLowerCase() + " @ " + coord.toString() + ".");
							if ((!TownyRegenAPI.hasProtectionRegenTask(new BlockLocation(block.getLocation()))) && (block.getType() != Material.TNT)) {
								ProtectionRegenTask task = new ProtectionRegenTask(plugin, block, false);
								task.setTaskId(plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, task, ((TownySettings.getPlotManagementWildRegenDelay() + count) * 20)));
								TownyRegenAPI.addProtectionRegenTask(task);
								event.setYield((float) 0.0);
								block.getDrops().clear();
							}
						}
					}
				}
			}
		}
		
	}
	
	/**
	 * Test if this location has explosions enabled.
	 * 
	 * @param world
	 * @param target
	 * @return true if allowed.
	 */
	public boolean locationCanExplode(TownyWorld world, Location target) {

		Coord coord = Coord.parseCoord(target);

		if (world.isWarZone(coord) && !TownyWarConfig.isAllowingExplosionsInWarZone()) {
			return false;
		}

		try {
			TownBlock townBlock = world.getTownBlock(coord);
			if (world.isUsingTowny() && !world.isForceExpl()) {
				if ((!townBlock.getPermissions().explosion) || (TownyUniverse.isWarTime() && TownySettings.isAllowWarBlockGriefing() && !townBlock.getTown().hasNation() && !townBlock.getTown().isBANG())) {
					return false;
				}
			}
		} catch (NotRegisteredException e) {
			return world.isExpl();
		}
		return true;
	}

}