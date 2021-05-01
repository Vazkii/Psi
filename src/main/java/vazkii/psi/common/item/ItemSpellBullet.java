/*
 * This class is distributed as part of the Psi Mod.
 * Get the Source Code in github:
 * https://github.com/Vazkii/Psi
 *
 * Psi is Open Source and distributed under the
 * Psi License: https://psi.vazkii.net/license.php
 */
package vazkii.psi.common.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Rarity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.*;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;

import vazkii.psi.api.PsiAPI;
import vazkii.psi.api.internal.TooltipHelper;
import vazkii.psi.api.spell.ISpellAcceptor;
import vazkii.psi.api.spell.Spell;
import vazkii.psi.api.spell.SpellContext;
import vazkii.psi.common.core.handler.PsiSoundHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ItemSpellBullet extends Item {

	public static final String TAG_SPELL = "spell";

	public ItemSpellBullet(Item.Properties properties) {
		super(properties.maxStackSize(16));
	}

	@Nullable
	@Override
	public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundNBT nbt) {
		return new SpellAcceptor(stack);
	}

	@Nonnull
	@Override
	public ITextComponent getDisplayName(@Nonnull ItemStack stack) {
		if (ISpellAcceptor.hasSpell(stack)) {
			CompoundNBT cmp = stack.getOrCreateTag().getCompound(TAG_SPELL);
			String name = cmp.getString(Spell.TAG_SPELL_NAME); // We don't need to load the whole spell just for the name
			if (name.isEmpty()) {
				return super.getDisplayName(stack);
			}
			return new StringTextComponent(name);
		}
		return super.getDisplayName(stack);
	}

	@Nonnull
	@Override
	@OnlyIn(Dist.CLIENT)
	public Rarity getRarity(ItemStack stack) {
		return ISpellAcceptor.hasSpell(stack) ? Rarity.RARE : Rarity.COMMON;
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World playerIn, List<ITextComponent> tooltip, ITooltipFlag advanced) {
		TooltipHelper.tooltipIfShift(tooltip, () -> {
			tooltip.add(new TranslationTextComponent("psimisc.bullet_type", new TranslationTextComponent("psi.bullet_type_" + getBulletType())));
			tooltip.add(new TranslationTextComponent("psimisc.bullet_cost", (int) (ISpellAcceptor.acceptor(stack).getCostModifier() * 100)));
		});
	}

	@Nonnull
	@Override
	public ActionResult<ItemStack> onItemRightClick(World worldIn, PlayerEntity playerIn, @Nonnull Hand hand) {
		ItemStack itemStackIn = playerIn.getHeldItem(hand);
		if (ItemSpellDrive.getSpell(itemStackIn) != null && playerIn.isSneaking()) {
			if (!worldIn.isRemote) {
				worldIn.playSound(null, playerIn.getPosX(), playerIn.getPosY(), playerIn.getPosZ(), PsiSoundHandler.compileError, SoundCategory.PLAYERS, 0.5F, 1F);
			} else {
				playerIn.swingArm(hand);
			}
			ItemSpellDrive.setSpell(itemStackIn, null);

			return new ActionResult<>(ActionResultType.SUCCESS, itemStackIn);
		}

		return new ActionResult<>(ActionResultType.PASS, itemStackIn);
	}

	public String getBulletType() {
		return "basic";
	}

	public Set<Entity> castSpell(ItemStack stack, SpellContext context) {
		context.cspell.safeExecute(context);
		return new HashSet<>();
	}

	public double getCostModifier(ItemStack stack) {
		return 1.0;
	}

	public boolean isCADOnlyContainer(ItemStack stack) {
		return false;
	}

	protected static class SpellAcceptor implements ICapabilityProvider, ISpellAcceptor {
		protected final ItemStack stack;
		private final LazyOptional<ISpellAcceptor> capOptional;

		protected SpellAcceptor(ItemStack stack) {
			this.stack = stack;
			this.capOptional = LazyOptional.of(() -> this);
		}

		private ItemSpellBullet bulletItem() {
			return ((ItemSpellBullet) stack.getItem());
		}

		@Nonnull
		@Override
		public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
			return PsiAPI.SPELL_ACCEPTOR_CAPABILITY.orEmpty(cap, capOptional);
		}

		@Override
		public void setSpell(PlayerEntity player, Spell spell) {
			if (stack.getCount() == 1) {
				ItemSpellDrive.setSpell(stack, spell);
				return;
			}
			stack.shrink(1);
			ItemStack newStack = stack.copy();
			newStack.setCount(1);
			ItemSpellDrive.setSpell(newStack, spell);
			if (!player.addItemStackToInventory(newStack)) {
				player.dropItem(newStack, false);
			}
		}

		@Override
		public Spell getSpell() {
			return ItemSpellDrive.getSpell(stack);
		}

		@Override
		public boolean containsSpell() {
			return stack.getOrCreateTag().getBoolean(ItemSpellDrive.HAS_SPELL);
		}

		@Override
		public void castSpell(SpellContext context) {
			bulletItem().castSpell(stack, context);
		}

		@Override
		public double getCostModifier() {
			return bulletItem().getCostModifier(stack);
		}

		@Override
		public boolean castableFromSocket() {
			return true;
		}

		@Override
		public boolean isCADOnlyContainer() {
			return bulletItem().isCADOnlyContainer(stack);
		}

		@Override
		public boolean requiresSneakForSpellSet() {
			return false;
		}
	}

}
