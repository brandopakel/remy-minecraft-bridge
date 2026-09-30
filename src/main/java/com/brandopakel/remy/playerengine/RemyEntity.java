package com.brandopakel.remy.playerengine;

import com.player2.playerengine.automaton.api.entity.IAutomatone;
import com.player2.playerengine.automaton.api.entity.IHungerManagerProvider;
import com.player2.playerengine.automaton.api.entity.IInteractionManagerProvider;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityHungerManager;
import com.player2.playerengine.automaton.api.entity.LivingEntityInteractionManager;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import java.util.UUID;

public class RemyEntity extends ZombieEntity
        implements IAutomatone, IInventoryProvider, IInteractionManagerProvider, IHungerManagerProvider {
    private static final String OWNER_UUID_KEY = "RemyOwnerUuid";
    private static final String OWNER_NAME_KEY = "RemyOwnerName";
    private static final String INVENTORY_KEY = "Inventory";
    private static final String SELECTED_SLOT_KEY = "SelectedItemSlot";
    private static final String HUNGER_KEY = "RemyHunger";

    private final LivingEntityInteractionManager interactionManager;
    private final LivingEntityInventory inventory;
    private final LivingEntityHungerManager hungerManager;

    private UUID ownerUuid;
    private String ownerName = "";

    public RemyEntity(EntityType<? extends RemyEntity> entityType, World world) {
        super(entityType, world);
        this.setStepHeight(0.6f);
        this.setMovementSpeed(0.4f);
        this.setAiDisabled(true);
        this.setCanPickUpLoot(false);
        this.interactionManager = new LivingEntityInteractionManager(this);
        this.inventory = new LivingEntityInventory(this);
        this.hungerManager = new LivingEntityHungerManager();
        this.setCustomName(Text.literal("remy"));
        this.setCustomNameVisible(true);
    }

    public void setOwner(UUID ownerUuid, String ownerName) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName == null ? "" : ownerName;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    @Override
    protected void initGoals() {
        // Navigation is driven by PlayerEngine/Baritone goals, not vanilla mob AI.
    }

    @Override
    public LivingEntityInventory getLivingInventory() {
        return inventory;
    }

    @Override
    public LivingEntityInteractionManager getInteractionManager() {
        return interactionManager;
    }

    @Override
    public LivingEntityHungerManager getHungerManager() {
        return hungerManager;
    }

    @Override
    public void tick() {
        this.setFireTicks(0);
        interactionManager.update();
        inventory.updateItems();
        if (!this.getWorld().isClient()) {
            this.getBaritone().serverTick();
        }
        super.tick();
    }

    @Override
    public void tickMovement() {
        super.tickMovement();
        pickupNearbyItems();
    }

    private void pickupNearbyItems() {
        if (this.getWorld().isClient() || !this.isAlive() || this.isRemoved()) {
            return;
        }
        if (!this.getWorld().getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return;
        }
        Vec3i radius = new Vec3i(2, 2, 2);
        for (ItemEntity itemEntity : this.getWorld().getNonSpectatingEntities(
                ItemEntity.class,
                this.getBoundingBox().expand(radius.getX(), radius.getY(), radius.getZ())
        )) {
            if (!itemEntity.isRemoved() && !itemEntity.getStack().isEmpty() && !itemEntity.cannotPickup()) {
                ItemStack stack = itemEntity.getStack();
                int count = stack.getCount();
                if (this.inventory.insertStack(stack)) {
                    this.sendPickup(itemEntity, count);
                    if (stack.isEmpty()) {
                        itemEntity.discard();
                        stack.setCount(count);
                    }
                }
            }
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid(OWNER_UUID_KEY)) {
            ownerUuid = nbt.getUuid(OWNER_UUID_KEY);
        }
        if (nbt.contains(OWNER_NAME_KEY)) {
            ownerName = nbt.getString(OWNER_NAME_KEY);
        }
        if (nbt.contains(INVENTORY_KEY)) {
            inventory.readNbt(nbt.getList(INVENTORY_KEY, NbtElement.COMPOUND_TYPE));
        }
        if (nbt.contains(SELECTED_SLOT_KEY)) {
            inventory.selectedSlot = nbt.getInt(SELECTED_SLOT_KEY);
        }
        if (nbt.contains(HUNGER_KEY)) {
            hungerManager.readNbt(nbt.getCompound(HUNGER_KEY));
        }
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (ownerUuid != null) {
            nbt.putUuid(OWNER_UUID_KEY, ownerUuid);
        }
        nbt.putString(OWNER_NAME_KEY, ownerName);
        nbt.put(INVENTORY_KEY, inventory.writeNbt(new NbtList()));
        nbt.putInt(SELECTED_SLOT_KEY, inventory.selectedSlot);
        NbtCompound hunger = new NbtCompound();
        hungerManager.writeNbt(hunger);
        nbt.put(HUNGER_KEY, hunger);
    }

    @Override
    public Arm getMainArm() {
        return Arm.RIGHT;
    }

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND) {
            return inventory.getMainHandStack();
        }
        if (slot == EquipmentSlot.OFFHAND) {
            return inventory.offHand.get(0);
        }
        if (slot.getType() == EquipmentSlot.Type.ARMOR) {
            return inventory.armor.get(slot.getEntitySlotId());
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND) {
            inventory.setStack(inventory.selectedSlot, stack);
        } else if (slot == EquipmentSlot.OFFHAND) {
            inventory.offHand.set(0, stack);
        } else if (slot.getType() == EquipmentSlot.Type.ARMOR) {
            inventory.armor.set(slot.getEntitySlotId(), stack);
        }
    }

    @Override
    protected boolean burnsInDaylight() {
        return false;
    }

    @Override
    public boolean isBaby() {
        return false;
    }

    @Override
    public boolean tryAttack(Entity target) {
        return false;
    }
}
