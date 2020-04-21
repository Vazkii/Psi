/*
 * This class is distributed as a part of the Psi Mod.
 * Get the Source Code on GitHub:
 * https://github.com/Vazkii/Psi
 *
 * Psi is Open Source and distributed under the
 * Psi License: https://psi.vazkii.net/license.php
 */
package vazkii.psi.common.crafting.recipe;

import com.google.gson.JsonObject;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipeSerializer;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistryEntry;

import vazkii.psi.api.PsiAPI;
import vazkii.psi.api.recipe.ITrickRecipe;
import vazkii.psi.api.spell.Spell;
import vazkii.psi.api.spell.SpellPiece;
import vazkii.psi.api.spell.piece.PieceCraftingTrick;
import vazkii.psi.common.crafting.ModCraftingRecipes;
import vazkii.psi.common.item.base.ModItems;

import javax.annotation.Nullable;

public class TrickRecipe implements ITrickRecipe {
	public static final IRecipeSerializer<TrickRecipe> SERIALIZER = new Serializer();
	private static final Spell dummySpell = new Spell();

	@Nullable
	private final PieceCraftingTrick piece;
	private final Ingredient input;
	private final ItemStack output;
	private final ItemStack cad;
	private final ResourceLocation id;

	public TrickRecipe(ResourceLocation id, @Nullable PieceCraftingTrick piece, Ingredient input, ItemStack output, ItemStack cad) {
		this.id = id;
		this.piece = piece;
		this.input = input;
		this.output = output;
		this.cad = cad;
	}

	@Nullable
	@Override
	public PieceCraftingTrick getPiece() {
		return piece;
	}

	@Override
	public Ingredient getInput() {
		return input;
	}

	@Override
	public ItemStack getRecipeOutput() {
		return output;
	}

	@Override
	public ItemStack getAssembly() {
		return cad;
	}

	@Override
	public boolean matches(RecipeWrapper inv, World world) {
		return getInput().test(inv.getStackInSlot(0));
	}

	@Override
	public ItemStack getCraftingResult(RecipeWrapper inv) {
		return getRecipeOutput();
	}

	@Override
	public ItemStack getIcon() {
		return new ItemStack(ModItems.cad);
	}

	@Override
	public ResourceLocation getId() {
		return id;
	}

	@Override
	public IRecipeSerializer<?> getSerializer() {
		return SERIALIZER;
	}

	@Override
	public boolean canFit(int width, int height) {
		return true;
	}

	@Override
	public IRecipeType<?> getType() {
		return ModCraftingRecipes.TRICK_RECIPE_TYPE;
	}

	static class Serializer extends ForgeRegistryEntry<IRecipeSerializer<?>> implements IRecipeSerializer<TrickRecipe> {
		@Override
		public TrickRecipe read(ResourceLocation id, JsonObject json) {
			Ingredient ingredient = Ingredient.deserialize(JSONUtils.getJsonObject(json, "input"));
			ItemStack output = CraftingHelper.getItemStack(JSONUtils.getJsonObject(json, "output"), true);
			ItemStack cadAssembly = CraftingHelper.getItemStack(JSONUtils.getJsonObject(json, "cad"), true);

			PieceCraftingTrick trick = null;
			if (json.has("trick")) {
				trick = PsiAPI.getSpellPieceRegistry().getValue(new ResourceLocation(JSONUtils.getString(json, "trick")))
						.filter(PieceCraftingTrick.class::isAssignableFrom)
						.map(clazz -> (PieceCraftingTrick) SpellPiece.create(clazz, dummySpell))
						.orElse(null);
			}
			return new TrickRecipe(id, trick, ingredient, output, cadAssembly);
		}

		@Nullable
		@Override
		public TrickRecipe read(ResourceLocation id, PacketBuffer buf) {
			Ingredient ingredient = Ingredient.read(buf);
			ItemStack output = buf.readItemStack();
			ItemStack cadAssembly = buf.readItemStack();
			PieceCraftingTrick trick = null;
			if (buf.readBoolean()) {
				trick = PsiAPI.getSpellPieceRegistry().getValue(buf.readResourceLocation())
						.map(clazz -> (PieceCraftingTrick) SpellPiece.create(clazz, dummySpell))
						.orElse(null);
			}
			return new TrickRecipe(id, trick, ingredient, output, cadAssembly);
		}

		@Override
		public void write(PacketBuffer buf, TrickRecipe recipe) {
			recipe.input.write(buf);
			buf.writeItemStack(recipe.output);
			buf.writeItemStack(recipe.cad);
			buf.writeBoolean(recipe.piece != null);
			if (recipe.piece != null) {
				buf.writeResourceLocation(recipe.piece.registryKey);
			}
		}
	}

}
