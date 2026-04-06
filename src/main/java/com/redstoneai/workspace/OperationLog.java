package com.redstoneai.workspace;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Timestamped log of AI operations performed on a workspace.
 * Stored in the controller block entity for review and auditing.
 * Persisted via NBT with a configurable maximum entry count.
 */
public class OperationLog {

    public record Entry(long timestamp, String source, String action, String detail) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("time", timestamp);
            tag.putString("src", source);
            tag.putString("act", action);
            tag.putString("det", detail);
            return tag;
        }

        public static Entry load(CompoundTag tag) {
            return new Entry(
                    tag.getLong("time"),
                    tag.getString("src"),
                    tag.getString("act"),
                    tag.getString("det")
            );
        }

        public String toDisplayString() {
            return "[" + source + "] " + action + (detail.isEmpty() ? "" : ": " + detail);
        }
    }

    private static final int MAX_ENTRIES = 500;
    private final List<Entry> entries = new ArrayList<>();

    public void log(String source, String action, String detail) {
        entries.add(new Entry(System.currentTimeMillis(), source, action, detail));
        trimToMaxEntries();
    }

    public void logAI(String action, String detail) {
        log("AI", action, detail);
    }

    public void logPlayer(String action, String detail) {
        log("Player", action, detail);
    }

    public void logSystem(String action, String detail) {
        log("System", action, detail);
    }

    public void replaceEntries(List<Entry> loadedEntries) {
        entries.clear();
        entries.addAll(loadedEntries);
        trimToMaxEntries();
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public List<Entry> getLastN(int n) {
        int from = Math.max(0, entries.size() - n);
        return entries.subList(from, entries.size());
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    private void trimToMaxEntries() {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            list.add(entry.save());
        }
        tag.put("entries", list);
        return tag;
    }

    public static OperationLog load(CompoundTag tag) {
        OperationLog log = new OperationLog();
        if (tag.contains("entries", Tag.TAG_LIST)) {
            ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                log.entries.add(Entry.load(list.getCompound(i)));
            }
        }
        return log;
    }
}
