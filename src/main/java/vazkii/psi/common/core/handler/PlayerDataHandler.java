/*
 * This class is distributed as part of the Psi Mod.
 * Get the Source Code in github:
 * https://github.com/Vazkii/Psi
 *
 * Psi is Open Source and distributed under the
 * Psi License: https://psi.vazkii.net/license.php
 */
package vazkii.psi.common.core.handler;

import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.advancements.Advancement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.play.server.SPlayerPositionLookPacket.Flags;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingJumpEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import vazkii.psi.api.PsiAPI;
import vazkii.psi.api.cad.EnumCADStat;
import vazkii.psi.api.cad.ICAD;
import vazkii.psi.api.cad.ICADColorizer;
import vazkii.psi.api.cad.ISocketable;
import vazkii.psi.api.cad.RegenPsiEvent;
import vazkii.psi.api.exosuit.IPsiEventArmor;
import vazkii.psi.api.exosuit.PsiArmorEvent;
import vazkii.psi.api.internal.IPlayerData;
import vazkii.psi.api.internal.PsiRenderHelper;
import vazkii.psi.api.internal.Vector3;
import vazkii.psi.api.spell.EnumSpellStat;
import vazkii.psi.api.spell.ISpellAcceptor;
import vazkii.psi.api.spell.LoopcastEndEvent;
import vazkii.psi.api.spell.PieceExecutedEvent;
import vazkii.psi.api.spell.PieceGroupAdvancementComplete;
import vazkii.psi.api.spell.PieceKnowledgeEvent;
import vazkii.psi.api.spell.Spell;
import vazkii.psi.api.spell.SpellContext;
import vazkii.psi.api.spell.SpellPiece;
import vazkii.psi.client.core.handler.ClientTickHandler;
import vazkii.psi.client.render.entity.RenderSpellCircle;
import vazkii.psi.common.Psi;
import vazkii.psi.common.item.ItemCAD;
import vazkii.psi.common.lib.LibMisc;
import vazkii.psi.common.network.MessageRegister;
import vazkii.psi.common.network.message.MessageDataSync;
import vazkii.psi.common.network.message.MessageDeductPsi;
import vazkii.psi.common.network.message.MessageTriggerJumpSpell;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.WeakHashMap;

public class PlayerDataHandler {

	private static final WeakHashMap<PlayerEntity, PlayerData> remotePlayerData = new WeakHashMap<>();
	private static final WeakHashMap<PlayerEntity, PlayerData> playerData = new WeakHashMap<>();
	public static final Set<SpellContext> delayedContexts = new HashSet<>();

	private static final String DATA_TAG = "PsiData";

	public static final DamageSource damageSourceOverload = new DamageSource("psi-overload").setDamageBypassesArmor().setMagicDamage();

	@Nonnull
	public static PlayerData get(PlayerEntity player) {
		if (player == null) {
			return new PlayerData();
		}

		Map<PlayerEntity, PlayerData> dataMap = player.world.isRemote ? remotePlayerData : playerData;

		PlayerData data = dataMap.computeIfAbsent(player, PlayerData::new);
		if (data.playerWR != null && data.playerWR.get() != player) {
			CompoundNBT cmp = new CompoundNBT();
			data.writeToNBT(cmp);
			dataMap.remove(player);
			data = get(player);
			data.readFromNBT(cmp);
		}

		return data;
	}

	public static CompoundNBT getDataCompoundForPlayer(PlayerEntity player) {
		CompoundNBT forgeData = player.getPersistentData();
		if (!forgeData.contains(PlayerEntity.PERSISTED_NBT_TAG)) {
			forgeData.put(PlayerEntity.PERSISTED_NBT_TAG, new CompoundNBT());
		}

		CompoundNBT persistentData = forgeData.getCompound(PlayerEntity.PERSISTED_NBT_TAG);
		if (!persistentData.contains(DATA_TAG)) {
			persistentData.put(DATA_TAG, new CompoundNBT());
		}

		return persistentData.getCompound(DATA_TAG);
	}

	@Mod.EventBusSubscriber(modid = LibMisc.MOD_ID)
	public static class EventHandler {

		@SubscribeEvent
		public static void onServerTick(TickEvent.ServerTickEvent event) {
			if (event.phase == TickEvent.Phase.END) {
				List<SpellContext> delayedContextsCopy = new ArrayList<>(delayedContexts);
				for (SpellContext context : delayedContextsCopy) {
					context.delay--;

					if (context.delay <= 0) {
						context.delay = 0; // Just in case it goes under 0
						context.cspell.safeExecute(context);

						if (context.delay == 0) {
							delayedContexts.remove(context);
						}
					}
				}
			}
		}

		@SubscribeEvent
		public static void onPlayerTick(LivingUpdateEvent event) {
			if (event.getEntityLiving() instanceof PlayerEntity) {
				PlayerEntity player = (PlayerEntity) event.getEntityLiving();

				ItemStack cadStack = PsiAPI.getPlayerCAD(player);
				if (!cadStack.isEmpty() && cadStack.getItem() instanceof ICAD && PsiAPI.canCADBeUpdated(player)) {
					((ICAD) cadStack.getItem()).incrementTime(cadStack);
				}

				PsiArmorEvent.post(new PsiArmorEvent(player, PsiArmorEvent.TICK));
				PlayerDataHandler.get(player).tick();
			}
		}

		@SubscribeEvent
		public static void onEntityDamage(LivingHurtEvent event) {
			if (event.getEntityLiving() instanceof PlayerEntity) {
				PlayerEntity player = (PlayerEntity) event.getEntityLiving();
				PlayerDataHandler.get(player).damage(event.getAmount());

				LivingEntity attacker = null;
				if (event.getSource().getTrueSource() != null && event.getSource().getTrueSource() instanceof LivingEntity) {
					attacker = (LivingEntity) event.getSource().getTrueSource();
				}

				PsiArmorEvent.post(new PsiArmorEvent(player, PsiArmorEvent.DAMAGE, event.getAmount(), attacker));
				if (event.getSource().isFireDamage()) {
					PsiArmorEvent.post(new PsiArmorEvent(player, PsiArmorEvent.ON_FIRE));
				}
				if (player.getHealth() - event.getAmount() <= 0.0 && !PlayerDataHandler.get(player).lowHp) {
					PsiArmorEvent.post(new PsiArmorEvent(player, PsiArmorEvent.LOW_HP));
					PlayerDataHandler.get(player).lowHp = true;
				}
			}
		}

		@SubscribeEvent
		public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
			if (event.getPlayer() instanceof ServerPlayerEntity) {
				MessageDataSync message = new MessageDataSync(get(event.getPlayer()));
				MessageRegister.sendToPlayer(message, event.getPlayer());
			}
		}

		@SubscribeEvent
		public static void onEntityJump(LivingJumpEvent event) {
			if (event.getEntityLiving() instanceof PlayerEntity && event.getEntity().world.isRemote) {
				PlayerEntity player = (PlayerEntity) event.getEntityLiving();
				PsiArmorEvent.post(new PsiArmorEvent(player, PsiArmorEvent.JUMP));
				MessageRegister.HANDLER.sendToServer(new MessageTriggerJumpSpell());
			}
		}

		@SubscribeEvent
		public static void onPsiArmorEvent(PsiArmorEvent event) {
			for (int i = 0; i < 4; i++) {
				ItemStack armor = ((PlayerEntity) event.getEntityLiving()).inventory.armorInventory.get(i);
				if (!armor.isEmpty() && armor.getItem() instanceof IPsiEventArmor) {
					IPsiEventArmor handler = (IPsiEventArmor) armor.getItem();
					handler.onEvent(armor, event);
				}
			}
		}

		@SubscribeEvent
		public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
			get(event.getPlayer()).eidosChangelog.clear();
		}

		@SubscribeEvent
		@OnlyIn(Dist.CLIENT)
		public static void onRenderWorldLast(RenderWorldLastEvent event) {
			Minecraft mc = Minecraft.getInstance();
			Entity cameraEntity = mc.getRenderViewEntity();
			if (cameraEntity != null) {
				float partialTicks = event.getPartialTicks();
				for (PlayerEntity player : mc.world.getPlayers()) {
					PlayerDataHandler.get(player).render(player, partialTicks, event.getMatrixStack());
				}
			}

		}

		@SubscribeEvent
		@OnlyIn(Dist.CLIENT)
		public static void onFOVUpdate(FOVUpdateEvent event) {
			PlayerData data = get(Minecraft.getInstance().player);
			if (data.isAnchored) {
				float fov = event.getNewfov();
				if (data.eidosAnchorTime > 0) {
					fov *= Math.min(5, data.eidosAnchorTime - ClientTickHandler.partialTicks) / 5;
				} else {
					fov *= (10 - Math.min(10, data.postAnchorRecallTime + ClientTickHandler.partialTicks)) / 10;
				}
				event.setNewfov(fov);
			}
		}

	}

	public static class PlayerData implements IPlayerData {

		private static final String TAG_AVAILABLE_PSI = "availablePsi";
		private static final String TAG_REGEN_CD = "regenCd";
		private static final String TAG_OVERFLOWED = "overflowed";

		private static final String TAG_EIDOS_ANCHOR_X = "eidosAnchorX";
		private static final String TAG_EIDOS_ANCHOR_Y = "eidosAnchorY";
		private static final String TAG_EIDOS_ANCHOR_Z = "eidosAnchorZ";
		private static final String TAG_EIDOS_ANCHOR_PITCH = "eidosAnchorPitch";
		private static final String TAG_EIDOS_ANCHOR_YAW = "eidosAnchorYaw";
		private static final String TAG_EIDOS_ANCHOR_TIME = "eidosAnchorTime";

		private static final String TAG_CUSTOM_DATA = "customData";

		public int totalPsi = 5000;
		public int regen = 25;

		public int availablePsi;
		public int lastAvailablePsi;
		public int regenCooldown;

		public boolean loopcasting = false;
		public Hand loopcastHand = null;
		public ItemStack lastTickLoopcastStack;

		public int loopcastTime = 1;
		public int loopcastAmount = 0;
		public int loopcastFadeTime = 0;
		public boolean overflowed = false;

		// Eidos stuff
		public final Stack<Vector3> eidosChangelog = new Stack<>();
		public Vector3 eidosAnchor = new Vector3(0, 0, 0);
		public double eidosAnchorPitch, eidosAnchorYaw;
		public boolean isAnchored;
		public boolean isReverting;
		public int eidosAnchorTime;
		public int postAnchorRecallTime;
		public int eidosReversionTime;
		public DimensionType lastDimension;

		// Exosuit Event Stuff
		private boolean lowLight, underwater, lowHp;

		public boolean deductTick;

		public final List<Deduction> deductions = new ArrayList<>();
		public final WeakReference<PlayerEntity> playerWR;
		private final boolean client;

		// Custom Data
		private CompoundNBT customData;

		private PlayerData() {
			playerWR = new WeakReference<>(null);
			client = true;
		}

		public PlayerData(PlayerEntity player) {
			playerWR = new WeakReference<>(player);
			client = player.getEntityWorld().isRemote;

			load();
		}

		public void tick() {
			PlayerEntity player = playerWR.get();
			if (player == null) {
				return;
			}

			DimensionType dimension = player.getEntityWorld().getDimensionType();

			if (deductTick) {
				deductTick = false;
			} else {
				lastAvailablePsi = availablePsi;
			}

			int max = getTotalPsi();
			if (availablePsi > max) {
				availablePsi = max;
			}

			ItemStack cadStack = getCAD();

			if (!cadStack.isEmpty()) {
				ICAD cad = (ICAD) cadStack.getItem();
				int overflow = cad.getStatValue(cadStack, EnumCADStat.OVERFLOW);
				if (overflow == -1) {
					availablePsi = max;
				} else {
					applyRegen(player, max, cadStack);
				}
			} else {
				applyRegen(player, max, cadStack);
			}

			int color = ICADColorizer.DEFAULT_SPELL_COLOR;

			if (!cadStack.isEmpty()) {
				color = Psi.proxy.getColorForCAD(cadStack);
			}

			float r = PsiRenderHelper.r(color) / 255F;
			float g = PsiRenderHelper.g(color) / 255F;
			float b = PsiRenderHelper.b(color) / 255F;

			loopcast: {
				if (player.isSpectator()) {
					stopLoopcast();
				}

				if (overflowed) {
					stopLoopcast();
				}

				if (loopcasting && loopcastHand != null) {
					ItemStack stackInHand = player.getHeldItem(loopcastHand);

					if (stackInHand.isEmpty() ||
							!ISocketable.isSocketable(stackInHand) ||
							!ISocketable.socketable(stackInHand).canLoopcast()) {
						stopLoopcast();
						break loopcast;
					}

					if (lastTickLoopcastStack != null) {
						if (!ItemStack.areItemsEqual(lastTickLoopcastStack, stackInHand) ||
								!ISocketable.isSocketable(lastTickLoopcastStack)) {
							stopLoopcast();
							break loopcast;
						} else {
							ISocketable lastTickItem = ISocketable.socketable(lastTickLoopcastStack);
							ISocketable thisTickItem = ISocketable.socketable(stackInHand);

							int lastSlot = lastTickItem.getSelectedSlot();
							int thisSlot = thisTickItem.getSelectedSlot();
							if (lastSlot != thisSlot) {
								stopLoopcast();
								break loopcast;
							}

							ItemStack lastTick = lastTickItem.getBulletInSocket(lastSlot);
							ItemStack thisTick = thisTickItem.getBulletInSocket(thisSlot);
							if (!ItemStack.areItemStacksEqual(lastTick, thisTick)) {
								stopLoopcast();
								break loopcast;
							}
						}
					}

					lastTickLoopcastStack = stackInHand.copy();

					ISocketable socketable = ISocketable.socketable(stackInHand);

					for (int i = 0; i < 5; i++) {
						double x = player.getPosX() + (Math.random() - 0.5) * 2.1 * player.getWidth();
						double y = player.getPosY() - player.getYOffset();
						double z = player.getPosZ() + (Math.random() - 0.5) * 2.1 * player.getWidth();
						float grav = -0.15F - (float) Math.random() * 0.03F;
						Psi.proxy.sparkleFX(x, y, z, r, g, b, grav, 0.25F, 15);
					}

					if (loopcastTime > 0 && loopcastTime % 5 == 0) {
						ItemStack bullet = socketable.getSelectedBullet();
						if (bullet.isEmpty() || !ISpellAcceptor.hasSpell(bullet)) {
							stopLoopcast();
							break loopcast;
						}

						ISpellAcceptor spellContainer = ISpellAcceptor.acceptor(bullet);
						Spell spell = spellContainer.getSpell();
						SpellContext context = new SpellContext().setPlayer(player).setSpell(spell).setLoopcastIndex(loopcastAmount + 1);
						context.castFrom = loopcastHand;
						if (context.isValid()) {
							if (context.cspell.metadata.evaluateAgainst(cadStack)) {
								int cost = ItemCAD.getRealCost(cadStack, bullet, context.cspell.metadata.getStat(EnumSpellStat.COST));
								if (cost > 0 || cost == -1) {
									if (cost != -1) {
										deductPsi(cost, 0, true);
									}

									if (!player.getEntityWorld().isRemote && loopcastTime % 10 == 0) {
										player.getEntityWorld().playSound(null, player.getPosX(), player.getPosY(), player.getPosZ(), PsiSoundHandler.loopcast, SoundCategory.PLAYERS, 0.1F, (float) (0.15 + Math.random() * 0.85));
									}
								}

								if (!player.getEntityWorld().isRemote) {
									context.cspell.safeExecute(context);
								}
								loopcastAmount++;
							}
						}
					}

					loopcastTime++;
				} else if (loopcastFadeTime > 0) {
					loopcastFadeTime--;
				}
			}

			if (!player.isAlive() || dimension != lastDimension) {
				eidosAnchorTime = 0;
				eidosReversionTime = 0;
				eidosChangelog.clear();
				isAnchored = false;
				isReverting = false;
			}

			if (eidosAnchorTime > 0) {
				if (eidosAnchorTime == 1) {
					if (player instanceof ServerPlayerEntity) {
						ServerPlayerEntity pmp = (ServerPlayerEntity) player;
						pmp.connection.setPlayerLocation(eidosAnchor.x, eidosAnchor.y, eidosAnchor.z, (float) eidosAnchorYaw, (float) eidosAnchorPitch);

						Entity riding = player.getRidingEntity();
						while (riding != null) {
							riding.setPosition(eidosAnchor.x, eidosAnchor.y, eidosAnchor.z);
							riding = riding.getRidingEntity();
						}
					}
					postAnchorRecallTime = 0;
				}
				eidosAnchorTime--;
			} else if (postAnchorRecallTime < 5) {
				postAnchorRecallTime--;
				isAnchored = false;
			}

			if (eidosReversionTime > 0) {
				if (eidosChangelog.isEmpty()) {
					eidosReversionTime = 0;
					isReverting = false;
				} else {
					eidosChangelog.pop();
					if (eidosChangelog.isEmpty()) {
						eidosReversionTime = 0;
						isReverting = false;
					} else {
						Vector3 vec = eidosChangelog.pop();
						if (player instanceof ServerPlayerEntity) {
							ServerPlayerEntity pmp = (ServerPlayerEntity) player;
							pmp.connection.setPlayerLocation(vec.x, vec.y, vec.z, 0, 0, ImmutableSet.of(Flags.X_ROT, Flags.Y_ROT));
							pmp.connection.captureCurrentPosition();
						} else {
							player.setPosition(vec.x, vec.y, vec.z);
						}

						Entity riding = player.getRidingEntity();
						while (riding != null) {
							riding.setPosition(vec.x, vec.y, vec.z);

							riding = riding.getRidingEntity();
						}

						if (player.world.isRemote) {
							for (int i = 0; i < 5; i++) {
								double spread = 0.6;

								double x = player.getPosX() + (Math.random() - 0.5) * spread;
								double y = player.getPosY() + (Math.random() - 0.5) * spread;
								double z = player.getPosZ() + (Math.random() - 0.5) * spread;

								Psi.proxy.sparkleFX(x, y, z, r, g, b, 0, 0, 0, 1.2F, 12);
							}
						}

						player.setMotion(0, 0, 0);
						player.fallDistance = 0F;
					}
				}

				eidosReversionTime--;
				if (eidosReversionTime == 0 || player.isSneaking()) {
					eidosChangelog.clear();
					isReverting = false;
				}
			} else {
				if (eidosChangelog.size() > 600) {
					eidosChangelog.remove(0);
				}
				eidosChangelog.push(Vector3.fromEntity(player));
			}

			BlockPos pos = player.getPosition();
			int light = player.getEntityWorld().getLightManager().getLightSubtracted(pos, 0);

			boolean lowLight = light <= 7;
			if (!this.lowLight && lowLight) {
				PsiArmorEvent.post(new PsiArmorEvent(player, PsiArmorEvent.LOW_LIGHT));
			}
			this.lowLight = lowLight;

			boolean underwater = player.isInWater();
			if (!this.underwater && underwater) {
				PsiArmorEvent.post(new PsiArmorEvent(player, PsiArmorEvent.UNDERWATER));
			}
			this.underwater = underwater;

			boolean lowHp = player.getHealth() <= 6;
			if (!this.lowHp && lowHp) {
				PsiArmorEvent.post(new PsiArmorEvent(player, PsiArmorEvent.LOW_HP));
			}
			this.lowHp = lowHp;

			List<Deduction> remove = new ArrayList<>();
			for (Deduction d : deductions) {
				if (d.invalid) {
					remove.add(d);
				} else {
					d.tick();
				}
			}
			deductions.removeAll(remove);

			lastDimension = dimension;
		}

		private void applyRegen(PlayerEntity player, int max, ItemStack cadStack) {
			RegenPsiEvent event = new RegenPsiEvent(player, this, cadStack);

			if (!MinecraftForge.EVENT_BUS.post(event)) {
				if (!cadStack.isEmpty()) {
					ICAD cad = (ICAD) cadStack.getItem();
					cad.regenPsi(cadStack, event.getCadRegen());
				}

				boolean anyChange = false;

				if (availablePsi != max && event.getPlayerRegen() > 0) {
					anyChange = true;
				}
				availablePsi = Math.min(max, availablePsi + event.getPlayerRegen());

				if (overflowed && event.willHealOverflow()) {
					anyChange = true;
					overflowed = false;
				}

				if (regenCooldown != event.getRegenCooldown()) {
					anyChange = true;
				}
				regenCooldown = event.getRegenCooldown();

				if (anyChange) {
					save();
				}
			}
		}

		public void stopLoopcast() {
			PlayerEntity player = playerWR.get();

			if (loopcasting) {
				loopcastFadeTime = 5;
				MinecraftForge.EVENT_BUS.post(new LoopcastEndEvent(player, this, loopcastHand, loopcastAmount));
			}
			loopcasting = false;

			lastTickLoopcastStack = null;
			loopcastHand = null;

			loopcastTime = 1;
			loopcastAmount = 0;

			if (player instanceof ServerPlayerEntity) {
				LoopcastTrackingHandler.syncForTrackersAndSelf((ServerPlayerEntity) player);
			}
		}

		public int calculateDamageDeduction(float amount) {
			return (int) (getTotalPsi() * 0.02 * amount);
		}

		public void damage(float amount) {
			int psi = calculateDamageDeduction(amount);
			if (psi > 0 && availablePsi > 0) {
				psi = Math.min(psi, availablePsi);
				deductPsi(psi, 20, true, true);
			}
		}

		public ItemStack getCAD() {
			return PsiAPI.getPlayerCAD(playerWR.get());
		}

		public void deductPsi(int psi, int cd, boolean sync) {
			deductPsi(psi, cd, sync, false);
		}

		@Override
		public void deductPsi(int psi, int cd, boolean sync, boolean shatter) {
			int currentPsi = availablePsi;

			PlayerEntity player = playerWR.get();
			if (player == null) {
				return;
			}

			ItemStack cadStack = getCAD();

			if (!cadStack.isEmpty()) {
				ICAD cad = (ICAD) cadStack.getItem();
				int storedPsi = cad.getStoredPsi(cadStack);
				if (storedPsi == -1) {
					return;
				}
			}

			availablePsi -= psi;
			if (regenCooldown < cd) {
				regenCooldown = cd;
			}

			if (availablePsi < 0) {
				int overflow = -availablePsi;
				availablePsi = 0;

				if (!cadStack.isEmpty()) {
					ICAD cad = (ICAD) cadStack.getItem();
					overflow = cad.consumePsi(cadStack, overflow);
				}

				if (!shatter && overflow > 0) {
					float dmg = (float) overflow / (loopcasting ? 50 : 125);
					if (!client) {
						player.attackEntityFrom(damageSourceOverload, dmg);
					}
					overflowed = true;
				}
			}

			if (sync && player instanceof ServerPlayerEntity) {
				MessageDeductPsi message = new MessageDeductPsi(currentPsi, availablePsi, regenCooldown, shatter);
				MessageRegister.sendToPlayer(message, player);
			}

			save();
		}

		public void addDeduction(int current, int deduct, boolean shatter) {
			if (deduct > current) {
				deduct = current;
			}
			if (deduct < 0) {
				deduct = 0;
			}

			if (deduct == 0) {
				return;
			}

			deductions.add(new Deduction(current, deduct, 20, shatter));
		}

		@Override
		public int getAvailablePsi() {
			return availablePsi;
		}

		@Override
		public int getLastAvailablePsi() {
			return lastAvailablePsi;
		}

		@Override
		public int getTotalPsi() {
			return totalPsi;
		}

		@Override
		public int getRegenPerTick() {
			return regen;
		}

		@Override
		public boolean isOverflowed() {
			return overflowed;
		}

		@Override
		public int getRegenCooldown() {
			return regenCooldown;
		}

		public boolean hasAdvancement(ResourceLocation group) {
			PlayerEntity player = playerWR.get();
			return Psi.proxy.hasAdvancement(group, player);
		}

		@Override
		public boolean isPieceGroupUnlocked(ResourceLocation group, @Nullable ResourceLocation name) {
			PlayerEntity player = playerWR.get();
			if (player == null) {
				return false;
			}

			if (player.isCreative()) {
				return true;
			}

			boolean hasAdvancement = hasAdvancement(group);
			PieceKnowledgeEvent event = new PieceKnowledgeEvent(group, name, player, this, hasAdvancement);
			MinecraftForge.EVENT_BUS.post(event);

			switch (event.getResult()) {
			case DENY:
				return false;
			default:
				return true;
			}
		}

		@Override
		public void unlockPieceGroup(ResourceLocation resourceLocation) {
			PlayerEntity player = playerWR.get();
			if (player instanceof ServerPlayerEntity) {
				ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
				Advancement advancement = serverPlayer.getServer().getAdvancementManager().getAdvancement(resourceLocation);
				if (advancement != null && !serverPlayer.getAdvancements().getProgress(advancement).isDone()) {
					for (String s : serverPlayer.getAdvancements().getProgress(advancement).getRemaningCriteria()) {
						serverPlayer.getAdvancements().getProgress(advancement).grantCriterion(s);
					}
				}
			}
		}

		@Override
		public void markPieceExecuted(SpellPiece piece) {
			PieceExecutedEvent event = new PieceExecutedEvent(piece, playerWR.get());
			MinecraftForge.EVENT_BUS.post(event);
			ResourceLocation advancement = PsiAPI.getGroupForPiece(piece.getClass());
			if (advancement != null && PsiAPI.getMainPieceForGroup(advancement) == piece.getClass() && !hasAdvancement(advancement)) {
				MinecraftForge.EVENT_BUS.post(new PieceGroupAdvancementComplete(piece, playerWR.get(), advancement));
			}
		}

		@Override
		public CompoundNBT getCustomData() {
			if (customData == null) {
				return customData = new CompoundNBT();
			}
			return customData;
		}

		@Override
		public void save() {
			if (!client) {
				PlayerEntity player = playerWR.get();

				if (player != null) {
					CompoundNBT cmp = getDataCompoundForPlayer(player);
					writeToNBT(cmp);
				}
			}
		}

		public void writeToNBT(CompoundNBT cmp) {
			cmp.putInt(TAG_AVAILABLE_PSI, availablePsi);
			cmp.putInt(TAG_REGEN_CD, regenCooldown);
			cmp.putBoolean(TAG_OVERFLOWED, overflowed);

			cmp.putDouble(TAG_EIDOS_ANCHOR_X, eidosAnchor.x);
			cmp.putDouble(TAG_EIDOS_ANCHOR_Y, eidosAnchor.y);
			cmp.putDouble(TAG_EIDOS_ANCHOR_Z, eidosAnchor.z);
			cmp.putDouble(TAG_EIDOS_ANCHOR_PITCH, eidosAnchorPitch);
			cmp.putDouble(TAG_EIDOS_ANCHOR_YAW, eidosAnchorYaw);
			cmp.putInt(TAG_EIDOS_ANCHOR_TIME, eidosAnchorTime);

			if (customData != null) {
				cmp.put(TAG_CUSTOM_DATA, customData);
			}
		}

		public void load() {
			if (!client) {
				PlayerEntity player = playerWR.get();

				if (player != null) {
					CompoundNBT cmp = getDataCompoundForPlayer(player);
					readFromNBT(cmp);
				}
			}
		}

		public void readFromNBT(CompoundNBT cmp) {
			availablePsi = cmp.getInt(TAG_AVAILABLE_PSI);
			regenCooldown = cmp.getInt(TAG_REGEN_CD);
			overflowed = cmp.getBoolean(TAG_OVERFLOWED);

			double x = cmp.getDouble(TAG_EIDOS_ANCHOR_X);
			double y = cmp.getDouble(TAG_EIDOS_ANCHOR_X);
			double z = cmp.getDouble(TAG_EIDOS_ANCHOR_X);
			eidosAnchor.set(x, y, z);
			eidosAnchorPitch = cmp.getDouble(TAG_EIDOS_ANCHOR_PITCH);
			eidosAnchorYaw = cmp.getDouble(TAG_EIDOS_ANCHOR_YAW);
			eidosAnchorTime = cmp.getInt(TAG_EIDOS_ANCHOR_TIME);

			customData = cmp.getCompound(TAG_CUSTOM_DATA);
		}

		@OnlyIn(Dist.CLIENT)
		public void render(PlayerEntity player, float partTicks, MatrixStack ms) {
			EntityRendererManager renderManager = Minecraft.getInstance().getRenderManager();
			double x = player.lastTickPosX + (player.getPosX() - player.lastTickPosX) * partTicks - renderManager.info.getProjectedView().x;
			double y = player.lastTickPosY + (player.getPosY() - player.lastTickPosY) * partTicks - renderManager.info.getProjectedView().y;
			double z = player.lastTickPosZ + (player.getPosZ() - player.lastTickPosZ) * partTicks - renderManager.info.getProjectedView().z;
			float scale = 0.75F;
			if (loopcasting) {
				float mul = Math.min(5F, loopcastTime + partTicks) / 5F;
				scale *= mul;
			} else if (loopcastFadeTime > 0) {
				float mul = Math.min(5F, loopcastFadeTime - partTicks) / 5F;
				scale *= mul;
			} else {
				return;
			}

			int color = ICADColorizer.DEFAULT_SPELL_COLOR;
			ItemStack cad = PsiAPI.getPlayerCAD(playerWR.get());
			if (!cad.isEmpty() && cad.getItem() instanceof ICAD) {
				ICAD icad = (ICAD) cad.getItem();
				color = icad.getSpellColor(cad);
			}

			ms.push();
			ms.translate(x, y + 0.15, z);
			IRenderTypeBuffer.Impl buffers = Minecraft.getInstance().getRenderTypeBuffers().getBufferSource();
			RenderSpellCircle.renderSpellCircle(ClientTickHandler.ticksInGame + partTicks, scale, 1, 0, -1, 0, color, ms, buffers);
			buffers.finish();
			ms.pop();
		}

		public static class Deduction {

			public final int current;
			public final int deduct;
			public final int cd;
			public final boolean shatter;

			public int elapsed;

			public boolean invalid;

			public Deduction(int current, int deduct, int cd, boolean shatter) {
				this.current = current;
				this.deduct = deduct;
				this.cd = cd;
				this.shatter = shatter;
			}

			public void tick() {
				elapsed++;

				if (elapsed >= cd) {
					invalid = true;
				}
			}

			public float getPercentile(float partTicks) {
				return 1F - Math.min(1F, (elapsed + partTicks) / cd);
			}
		}

	}
}
