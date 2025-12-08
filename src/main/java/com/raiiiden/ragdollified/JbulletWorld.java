package com.raiiiden.ragdollified;

import com.bulletphysics.collision.broadphase.AxisSweep3;
import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.dispatch.CollisionConfiguration;
import com.bulletphysics.collision.dispatch.CollisionDispatcher;
import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.dispatch.DefaultCollisionConfiguration;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.constraintsolver.ConstraintSolver;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class JbulletWorld {

    private static final Map<ServerLevel, JbulletWorld> INSTANCES = new ConcurrentHashMap<>();

    public static JbulletWorld get(ServerLevel level) {
        // One manager per dimension
        return INSTANCES.computeIfAbsent(level, JbulletWorld::new);
    }

    private final ServerLevel level;

    private BroadphaseInterface broadphase;
    private CollisionConfiguration collisionConfig;
    private CollisionDispatcher dispatcher;
    private ConstraintSolver solver;
    private DiscreteDynamicsWorld dynamicsWorld;

    // Shared collision cache for performance
    private final Map<BlockPos, CachedCollisionData> collisionCache = new HashMap<>();
    private static final long CACHE_LIFETIME_TICKS = 10; // Cache for 10 ticks

    private static class CachedCollisionData {
        final List<RigidBody> bodies;
        final long createdTick;
        int refCount = 0; // Track how many ragdolls are using this

        CachedCollisionData(List<RigidBody> bodies, long tick) {
            this.bodies = bodies;
            this.createdTick = tick;
        }
    }

    public JbulletWorld(ServerLevel level) {
        this.level = level;
        collisionConfig = new DefaultCollisionConfiguration();
        dispatcher = new CollisionDispatcher(collisionConfig);

        // Large but limited world AABB — still not doing full world collisions
        Vector3f worldAabbMin = new Vector3f(-10000f, -10000f, -10000f);
        Vector3f worldAabbMax = new Vector3f(10000f, 10000f, 10000f);
        broadphase = new AxisSweep3(worldAabbMin, worldAabbMax);

        solver = new SequentialImpulseConstraintSolver();

        dynamicsWorld = new DiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
        dynamicsWorld.setGravity(new Vector3f(0f, -9.81f, 0f));
        dynamicsWorld.getSolverInfo().numIterations = 20;
    }

    public void step(float dt) {
        // fixed-step, with substeps
        dynamicsWorld.stepSimulation(dt, 30, 1f/120f);

        // Clean up old cache entries
        long currentTick = level.getServer().getTickCount();
        collisionCache.entrySet().removeIf(entry -> {
            CachedCollisionData data = entry.getValue();
            // Remove if old and not in use
            if (data.refCount == 0 && (currentTick - data.createdTick) > CACHE_LIFETIME_TICKS) {
                // Clean up the bodies from the world
                for (RigidBody body : data.bodies) {
                    dynamicsWorld.removeRigidBody(body);
                }
                return true;
            }
            return false;
        });
    }

    // Get or create cached collision geometry for a region
    public List<RigidBody> getOrCreateCollisionGeometry(BlockPos center,
                                                        java.util.function.Supplier<List<RigidBody>> creator) {
        long currentTick = level.getServer().getTickCount();

        CachedCollisionData cached = collisionCache.get(center);
        if (cached != null && (currentTick - cached.createdTick) <= CACHE_LIFETIME_TICKS) {
            cached.refCount++;
            return cached.bodies;
        }

        // Create new collision geometry
        List<RigidBody> newBodies = creator.get();
        CachedCollisionData newData = new CachedCollisionData(newBodies, currentTick);
        newData.refCount = 1;
        collisionCache.put(center, newData);

        return newBodies;
    }

    // Release reference to cached collision geometry
    public void releaseCollisionGeometry(BlockPos center) {
        CachedCollisionData cached = collisionCache.get(center);
        if (cached != null) {
            cached.refCount = Math.max(0, cached.refCount - 1);
        }
    }

    public DiscreteDynamicsWorld getDynamicsWorld() {
        return dynamicsWorld;
    }

    public CollisionDispatcher getDispatcher() {
        return dispatcher;
    }

    public DiscreteDynamicsWorld getWorld() {
        return dynamicsWorld;
    }

    public ServerLevel getLevel() {
        return level;
    }
}