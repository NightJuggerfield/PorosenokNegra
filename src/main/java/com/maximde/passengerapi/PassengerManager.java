package com.maximde.passengerapi;

import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.maximde.passengerapi.events.AsyncAddPassengerEvent;
import com.maximde.passengerapi.events.AsyncPassengerPacketEvent;
import com.maximde.passengerapi.events.AsyncRemovePassengerEvent;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class PassengerManager {
    /**
     * String -> Plugin name
     * Integer -> Passenger entity ID
     * Set<Integer> -> The passenger ID's for this entity
     */
    @Getter
    private final Map<String, ConcurrentMap<Integer, Set<Integer>>> passengersHashmap = new ConcurrentHashMap<>();
    private final PlayerManager playerManager;
    private final String PLUGIN_NAME = "PassengerAPI (Internal)";
    private final PassengerAPI passengerAPI;

    public PassengerManager(PlayerManager playerManager, PassengerAPI passengerAPI) {
        this.playerManager = playerManager;
        this.passengerAPI = passengerAPI;
    }

    public PassengerActions initActions(JavaPlugin plugin) {
        String pluginName = plugin.getDescription().getName();
        return new PassengerActionsImpl(pluginName);
    }

    public int getTotalPassengersCount() {
        return passengersHashmap.values().stream()
                .flatMap(map -> map.values().stream())
                .mapToInt(Set::size)
                .sum();
    }

    public int getTotalTargetEntitiesCount() {
        return passengersHashmap.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    public void removePassenger(boolean async, int targetEntity, int passengerID, boolean sendPackets) {
        AsyncRemovePassengerEvent removePassengerEvent = new AsyncRemovePassengerEvent(async, targetEntity, Set.of(passengerID), PLUGIN_NAME);
        Bukkit.getPluginManager().callEvent(removePassengerEvent);
        if (removePassengerEvent.isCancelled()) return;
        for (Map<Integer, Set<Integer>> pluginPassengers : passengersHashmap.values()) {
            if (pluginPassengers == null) continue;
            Set<Integer> passengers = pluginPassengers.get(targetEntity);
            if (passengers == null) continue; // FIX: Was return, which was a bug
            passengers.remove(passengerID);
            if (passengers.isEmpty()) {
                pluginPassengers.remove(targetEntity);
            }
        }
        if (sendPackets) sendPassengerPacket(async,targetEntity);
    }

    /**
     * Internal method for the PassengerAPI
     * Don't even try to use it somehow in your own plugin!
     */
    public void removePassengers(boolean async, int[] passengerIDs, boolean sendPackets) {
        Set<Integer> passengerSet = Arrays.stream(passengerIDs).boxed().collect(Collectors.toSet());
        AsyncRemovePassengerEvent removePassengerEvent = new AsyncRemovePassengerEvent(async,-1, passengerSet, PLUGIN_NAME);
        Bukkit.getPluginManager().callEvent(removePassengerEvent);
        if (removePassengerEvent.isCancelled()) return;

        // For each plugin's map of passengers
        passengersHashmap.forEach((pluginName, pluginMap) -> {
            // For each target entity in that map
            pluginMap.forEach((targetEntity, passengers) -> {
                // Remove the specified passengers
                passengers.removeAll(passengerSet);
            });
            // After removal, clean up any target entities that now have no passengers
            pluginMap.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        });
        // After cleaning up inner maps, clean up any plugin entries that are now empty
        passengersHashmap.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        if (sendPackets) sendPassengerPackets(false);
    }

    /**
     * Internal method for the PassengerAPI
     * Don't even try to use it somehow in your own plugin!
     */
    public void addPassengers(boolean async, int targetEntity, int[] passengerIDs, boolean sendPackets) {
        Set<Integer> passengerSet = Arrays.stream(passengerIDs)
                .boxed()
                .collect(Collectors.toSet());

        AsyncAddPassengerEvent addPassengerEvent = new AsyncAddPassengerEvent(async, targetEntity, passengerSet, PLUGIN_NAME);
        Bukkit.getPluginManager().callEvent(addPassengerEvent);
        if (addPassengerEvent.isCancelled()) return;
        passengersHashmap.computeIfAbsent(PLUGIN_NAME, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(targetEntity, k -> ConcurrentHashMap.newKeySet())
                .addAll(passengerSet);

        if (sendPackets) sendPassengerPacket(async, targetEntity);
    }


    private class PassengerActionsImpl implements PassengerActions {
        private final String pluginName;

        PassengerActionsImpl(String pluginName) {
            this.pluginName = pluginName;
        }

        @Override
        public void addPassenger(boolean async, int targetEntity, int passengerEntity) {
            AsyncAddPassengerEvent addPassengerEvent = new AsyncAddPassengerEvent(async, targetEntity, Set.of(passengerEntity), pluginName);
            Bukkit.getPluginManager().callEvent(addPassengerEvent);
            if (addPassengerEvent.isCancelled()) return;
            passengersHashmap.computeIfAbsent(pluginName, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(targetEntity, k -> ConcurrentHashMap.newKeySet())
                    .add(passengerEntity);
            sendPassengerPacket(async,targetEntity);
        }

        @Override
        public void addPassengers(boolean async, int targetEntity, @NotNull Set<Integer> passengerIDs) {
            AsyncAddPassengerEvent addPassengerEvent = new AsyncAddPassengerEvent(async, targetEntity, passengerIDs, pluginName);
            Bukkit.getPluginManager().callEvent(addPassengerEvent);
            if (addPassengerEvent.isCancelled()) return;
            passengersHashmap.computeIfAbsent(pluginName, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(targetEntity, k -> ConcurrentHashMap.newKeySet())
                    .addAll(passengerIDs);
            sendPassengerPacket(async,targetEntity);
        }

        @Override
        public void addPassengers(boolean async, int targetEntity, int[] passengerIDs) {
            Set<Integer> passengerSet = Arrays.stream(passengerIDs)
                    .boxed()
                    .collect(Collectors.toSet());

            AsyncAddPassengerEvent addPassengerEvent = new AsyncAddPassengerEvent(async, targetEntity, passengerSet, pluginName);
            Bukkit.getPluginManager().callEvent(addPassengerEvent);
            if (addPassengerEvent.isCancelled()) return;
            passengersHashmap.computeIfAbsent(pluginName, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(targetEntity, k -> ConcurrentHashMap.newKeySet())
                    .addAll(passengerSet);

            sendPassengerPacket(async,targetEntity);
        }

        @Override
        public void removePassenger(boolean async, int targetEntity, int passengerID) {
            AsyncRemovePassengerEvent removePassengerEvent = new AsyncRemovePassengerEvent(async, targetEntity, Set.of(passengerID), pluginName);
            Bukkit.getPluginManager().callEvent(removePassengerEvent);
            if (removePassengerEvent.isCancelled()) return;
            Map<Integer, Set<Integer>> pluginPassengers = passengersHashmap.get(pluginName);
            if (pluginPassengers == null) return;
            Set<Integer> passengers = pluginPassengers.get(targetEntity);
            if (passengers == null) return;
            passengers.remove(passengerID);
            if (passengers.isEmpty()) {
                pluginPassengers.remove(targetEntity);
                if (pluginPassengers.isEmpty()) {
                    passengersHashmap.remove(pluginName);
                }
            }
            sendPassengerPacket(async,targetEntity);
        }

        @Override
        public void removePassenger(boolean async, int passengerID) {
            AsyncRemovePassengerEvent removePassengerEvent = new AsyncRemovePassengerEvent(async, -1, Set.of(passengerID), pluginName);
            Bukkit.getPluginManager().callEvent(removePassengerEvent);
            if (removePassengerEvent.isCancelled()) return;

            Map<Integer, Set<Integer>> pluginPassengers = passengersHashmap.get(pluginName);
            if (pluginPassengers == null) return;

            pluginPassengers.values().forEach(passengers -> passengers.remove(passengerID));
            pluginPassengers.entrySet().removeIf(entry -> entry.getValue().isEmpty());

            if (pluginPassengers.isEmpty()) {
                passengersHashmap.remove(pluginName);
            }

            sendPassengerPackets(async, pluginName);
        }

        @Override
        public void removePassengers(boolean async, int targetEntity, @NotNull Set<Integer> passengerIDs) {
            AsyncRemovePassengerEvent removePassengerEvent = new AsyncRemovePassengerEvent(async, targetEntity, passengerIDs, pluginName);
            Bukkit.getPluginManager().callEvent(removePassengerEvent);
            if (removePassengerEvent.isCancelled()) return;
            Map<Integer, Set<Integer>> pluginPassengers = passengersHashmap.get(pluginName);
            if (pluginPassengers == null) return;
            Set<Integer> passengers = pluginPassengers.get(targetEntity);
            if (passengers == null) return;
            passengers.removeAll(passengerIDs);
            if (passengers.isEmpty()) {
                pluginPassengers.remove(targetEntity);
                if (pluginPassengers.isEmpty()) {
                    passengersHashmap.remove(pluginName);
                }
            }
            sendPassengerPacket(async,targetEntity);
        }

        @Override
        public void removePassengers(boolean async, int targetEntity, int[] passengerIDs) {
            Set<Integer> passengerSet = Arrays.stream(passengerIDs)
                    .boxed()
                    .collect(Collectors.toSet());
            removePassengers(async, targetEntity, passengerSet);
        }

        @Override
        public void removePassengers(boolean async, int[] passengerIDs) {
            Set<Integer> passengerSet = Arrays.stream(passengerIDs).boxed().collect(Collectors.toSet());
            removePassengers(async, passengerSet);
        }

        @Override
        public void removePassengers(boolean async, @NotNull Set<Integer> passengerIDs) {
            AsyncRemovePassengerEvent removePassengerEvent = new AsyncRemovePassengerEvent(async, -1, passengerIDs, pluginName);
            Bukkit.getPluginManager().callEvent(removePassengerEvent);
            if (removePassengerEvent.isCancelled()) return;

            Map<Integer, Set<Integer>> pluginPassengers = passengersHashmap.get(pluginName);
            if (pluginPassengers == null) return;

            pluginPassengers.values().forEach(passengers -> passengers.removeAll(passengerIDs));
            pluginPassengers.entrySet().removeIf(entry -> entry.getValue().isEmpty());

            if (pluginPassengers.isEmpty()) {
                passengersHashmap.remove(pluginName);
            }

            sendPassengerPackets(async, pluginName);
        }

        @Override
        public void removeAllPassengers(boolean async, int targetEntity) {
            Map<Integer, Set<Integer>> pluginPassengers = passengersHashmap.get(pluginName);
            if (pluginPassengers == null) return;

            Set<Integer> passengersToRemove = pluginPassengers.getOrDefault(targetEntity, Set.of());
            AsyncRemovePassengerEvent removePassengerEvent = new AsyncRemovePassengerEvent(async, targetEntity, passengersToRemove, pluginName);
            Bukkit.getPluginManager().callEvent(removePassengerEvent);
            if (removePassengerEvent.isCancelled()) return;

            if (pluginPassengers.remove(targetEntity) != null) {
                if (pluginPassengers.isEmpty()) {
                    passengersHashmap.remove(pluginName);
                }
            }
            sendPassengerPacket(async,targetEntity);
        }

        @Override
        public Set<Integer> getPassengers(boolean async, int targetEntity) {
            Map<Integer, Set<Integer>> pluginPassengers = passengersHashmap.get(pluginName);
            if (pluginPassengers == null) return Set.of();
            Set<Integer> passengers = pluginPassengers.get(targetEntity);
            return passengers != null ? new HashSet<>(passengers) : Set.of();
        }


        @Override
        public void removeGlobalPassengers(boolean async, int targetEntity, @NotNull Set<Integer> passengerIDs) {
            passengersHashmap.values().forEach(map -> {
                Set<Integer> passengers = map.get(targetEntity);
                if (passengers != null) {
                    passengers.removeAll(passengerIDs);
                    if (passengers.isEmpty()) {
                        map.remove(targetEntity);
                    }
                }
            });
            passengersHashmap.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            sendPassengerPacket(async,targetEntity);
        }

        @Override
        public void removeAllGlobalPassengers(boolean async, int targetEntity) {
            passengersHashmap.values().forEach(map -> map.remove(targetEntity));
            passengersHashmap.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            sendPassengerPacket(async, targetEntity);
        }


        @Override
        public Set<Integer> getGlobalPassengers(boolean async, int targetEntity) {
            Set<Integer> allPassengers = ConcurrentHashMap.newKeySet();
            passengersHashmap.values().forEach(map -> {
                Set<Integer> passengers = map.get(targetEntity);
                if (passengers != null) {
                    allPassengers.addAll(passengers);
                }
            });
            return allPassengers;
        }


    }

    private void sendPassengerPackets(boolean async) {
        passengersHashmap.keySet().forEach(key -> sendPassengerPackets(async, key));
    }

    private void sendPassengerPackets(boolean async, String pluginName) {
        Map<Integer, Set<Integer>> pluginMap = passengersHashmap.get(pluginName);
        if (pluginMap != null) {
            pluginMap.keySet().forEach(entity -> sendPassengerPacket(async, entity));
        }
    }

    private void sendPassengerPacket(boolean async, int targetEntity) {
        Set<Integer> allPassengersList = passengersHashmap.values().stream()
                .map(map -> map.get(targetEntity))
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        int[] allPassengersArray = allPassengersList.stream().mapToInt(Integer::intValue).toArray();

        List<Player> receivers = new ArrayList<>(Bukkit.getOnlinePlayers());
        AsyncPassengerPacketEvent passengerPacketEvent = new AsyncPassengerPacketEvent(async, targetEntity, allPassengersList, receivers);
        WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(targetEntity, allPassengersArray);
        Bukkit.getPluginManager().callEvent(passengerPacketEvent);
        if(passengerPacketEvent.isCancelled()) return;
        
        passengerPacketEvent.getPacketReceivers().forEach(player -> {
            this.playerManager.sendPacketSilently(player, packet);
        });
    }
}