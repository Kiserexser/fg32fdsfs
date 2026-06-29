package sense.modules.impl.combat;

import sense.modules.Module;
import sense.modules.setting.impl.BooleanSetting;
import sense.modules.setting.impl.NumberSetting;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class SimpleAura extends Module {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private final Random random = new Random();

    // Настройки
    private final NumberSetting range = new NumberSetting("Дистанция", 3.0, 1.0, 6.0, 0.1);
    private final NumberSetting delayMin = new NumberSetting("Мин. задержка (мс)", 50, 0, 200, 10);
    private final NumberSetting delayMax = new NumberSetting("Макс. задержка (мс)", 150, 0, 300, 10);
    private final BooleanSetting sprintReset = new BooleanSetting("Сброс спринта", true);
    private final BooleanSetting onlyVisible = new BooleanSetting("Только видимые", true);

    private LivingEntity target = null;
    private long lastHitTime = 0;

    public SimpleAura() {
        super("SimpleAura", "Упрощённая KillAura");
        addSettings(range, delayMin, delayMax, sprintReset, onlyVisible);
    }

    @Override
    public void onEnable() {
        target = null;
        lastHitTime = 0;
    }

    @Override
    public void onDisable() {
        target = null;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;

        // 1. Обновить цель
        if (target == null || !isValid(target)) {
            target = findTarget();
        }
        if (target == null) return;

        double dist = mc.player.distanceTo(target);

        // 2. Проверка видимости (raycast)
        boolean canHit = true;
        if (onlyVisible.isEnabled()) {
            HitResult hit = mc.player.raycast(range.getCurrent(), 1.0f, false);
            canHit = hit instanceof EntityHitResult && ((EntityHitResult) hit).getEntity() == target;
        }

        // 3. Условия атаки
        if (!canHit || dist > range.getCurrent() || dist < 0.5) {
            // Не атакуем, но цель остаётся
            return;
        }

        // 4. Плавная ротация (без сложных алгоритмов)
        smoothRotate(target);

        // 5. Атака с задержкой (рандомная)
        long now = System.currentTimeMillis();
        long delay = (long) (delayMin.getCurrent() + (delayMax.getCurrent() - delayMin.getCurrent()) * random.nextDouble());
        if (now - lastHitTime < delay) return;

        // 6. Ванильный кулдаун
        if (mc.player.getAttackCooldownProgress(0.0F) < 0.9F) return;

        // 7. Сброс спринта (если включено)
        if (sprintReset.isEnabled() && mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }

        // 8. Атака
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(mc.player.getActiveHand());
        lastHitTime = now;
    }

    private LivingEntity findTarget() {
        Box box = mc.player.getBoundingBox().expand(range.getCurrent());
        List<LivingEntity> entities = mc.world.getEntitiesByClass(LivingEntity.class, box,
                e -> e != mc.player && e.isAlive() && !e.isDead() && isValid(e));
        entities.sort(Comparator.comparingDouble(e -> mc.player.distanceTo(e)));
        return entities.isEmpty() ? null : entities.get(0);
    }

    private boolean isValid(LivingEntity entity) {
        if (entity.isDead() || !entity.isAlive()) return false;
        if (entity instanceof PlayerEntity) {
            // можно добавить проверку на друзей
            return true;
        }
        // разрешить мобов по желанию
        return false;
    }

    private void smoothRotate(LivingEntity target) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.5, 0);
        double dx = targetPos.x - eye.x;
        double dy = targetPos.y - eye.y;
        double dz = targetPos.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180f / Math.PI) - 90f;
        float targetPitch = (float) (-MathHelper.atan2(dy, hDist) * 180f / Math.PI);

        // Джиттер (естественное дрожание)
        float jitterYaw = (random.nextFloat() - 0.5f) * 0.1f;
        float jitterPitch = (random.nextFloat() - 0.5f) * 0.1f;
        targetYaw += jitterYaw;
        targetPitch += jitterPitch;

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();
        float smoothSpeed = 0.2f;

        float yawDiff = targetYaw - currentYaw;
        yawDiff = (yawDiff % 360 + 540) % 360 - 180;
        float pitchDiff = targetPitch - currentPitch;

        mc.player.setYaw(currentYaw + yawDiff * smoothSpeed);
        mc.player.setPitch(currentPitch + pitchDiff * smoothSpeed);
    }
}
