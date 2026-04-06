package com.redstoneai.workspace;

import com.redstoneai.config.RAIConfig;
import com.redstoneai.registry.RAIBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Block entity for the workspace controller. Stores:
 * <ul>
 *   <li>Workspace name and configuration (size, protection mode)</li>
 *   <li>Initial state snapshot (for one-click revert)</li>
 *   <li>Operation log (AI actions history)</li>
 *   <li>AI chat messages (conversation history visible in GUI)</li>
 * </ul>
 * All data is persisted via NBT so it survives server restarts.
 */
public class WorkspaceControllerBlockEntity extends BlockEntity {
    private String workspaceName = "";
    private int sizeX = 16;
    private int sizeY = 8;
    private int sizeZ = 16;

    @Nullable
    private InitialSnapshot initialSnapshot;
    private final OperationLog operationLog = new OperationLog();
    private final List<ChatMessage> chatHistory = new ArrayList<>();

    private static final int MAX_CHAT_MESSAGES = 200;

    public record ChatMessage(long timestamp, String role, String content) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("time", timestamp);
            tag.putString("role", role);
            tag.putString("content", content);
            return tag;
        }

        public static ChatMessage load(CompoundTag tag) {
            return new ChatMessage(
                    tag.getLong("time"),
                    tag.getString("role"),
                    tag.getString("content")
            );
        }
    }

    public WorkspaceControllerBlockEntity(BlockPos pos, BlockState state) {
        super(RAIBlockEntities.WORKSPACE_CONTROLLER.get(), pos, state);
    }

    // ── Workspace Name ───────────────────────────────────────────────

    public String getWorkspaceName() { return workspaceName; }

    public void setWorkspaceName(String name) {
        this.workspaceName = name;
        setChanged();
    }

    // ── Size Configuration ───────────────────────────────────────────

    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }

    public void setSize(int x, int y, int z) {
        int maxSize = RAIConfig.SERVER.maxWorkspaceSize.get();
        this.sizeX = Math.max(4, Math.min(maxSize, x));
        this.sizeY = Math.max(4, Math.min(maxSize, y));
        this.sizeZ = Math.max(4, Math.min(maxSize, z));
        setChanged();
    }

    // ── Initial Snapshot ─────────────────────────────────────────────

    @Nullable
    public InitialSnapshot getInitialSnapshot() { return initialSnapshot; }

    public void setInitialSnapshot(@Nullable InitialSnapshot snapshot) {
        this.initialSnapshot = snapshot;
        setChanged();
    }

    public boolean hasInitialSnapshot() { return initialSnapshot != null; }

    // ── Operation Log ────────────────────────────────────────────────

    public OperationLog getOperationLog() { return operationLog; }

    // ── Chat History ─────────────────────────────────────────────────

    public void addChatMessage(String role, String content) {
        chatHistory.add(new ChatMessage(System.currentTimeMillis(), role, content));
        trimChatHistory();
        setChanged();
    }

    public List<ChatMessage> getChatHistory() {
        return Collections.unmodifiableList(chatHistory);
    }

    public void replaceChatHistory(List<ChatMessage> messages) {
        chatHistory.clear();
        chatHistory.addAll(messages);
        trimChatHistory();
        setChanged();
    }

    public void copyStateFrom(WorkspaceControllerBlockEntity source) {
        this.workspaceName = source.workspaceName;
        this.sizeX = source.sizeX;
        this.sizeY = source.sizeY;
        this.sizeZ = source.sizeZ;
        this.initialSnapshot = source.initialSnapshot;
        this.operationLog.replaceEntries(source.operationLog.getEntries());
        this.chatHistory.clear();
        this.chatHistory.addAll(source.chatHistory);
        trimChatHistory();
        setChanged();
    }

    public void clearWorkspaceBinding() {
        this.workspaceName = "";
        this.initialSnapshot = null;
        setChanged();
    }

    public void clearChatHistory() {
        chatHistory.clear();
        setChanged();
    }

    private void trimChatHistory() {
        while (chatHistory.size() > MAX_CHAT_MESSAGES) {
            chatHistory.remove(0);
        }
    }

    // ── NBT Persistence ──────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("workspaceName", workspaceName);
        tag.putInt("sizeX", sizeX);
        tag.putInt("sizeY", sizeY);
        tag.putInt("sizeZ", sizeZ);

        if (initialSnapshot != null) {
            tag.put("initialSnapshot", initialSnapshot.save());
        }

        tag.put("operationLog", operationLog.save());

        ListTag chatList = new ListTag();
        for (ChatMessage msg : chatHistory) {
            chatList.add(msg.save());
        }
        tag.put("chatHistory", chatList);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        workspaceName = tag.getString("workspaceName");
        sizeX = tag.contains("sizeX") ? tag.getInt("sizeX") : 16;
        sizeY = tag.contains("sizeY") ? tag.getInt("sizeY") : 8;
        sizeZ = tag.contains("sizeZ") ? tag.getInt("sizeZ") : 16;

        initialSnapshot = tag.contains("initialSnapshot", Tag.TAG_COMPOUND)
                ? InitialSnapshot.load(tag.getCompound("initialSnapshot"))
                : null;

        operationLog.clear();
        if (tag.contains("operationLog", Tag.TAG_COMPOUND)) {
            OperationLog loaded = OperationLog.load(tag.getCompound("operationLog"));
            operationLog.replaceEntries(loaded.getEntries());
        }

        chatHistory.clear();
        if (tag.contains("chatHistory", Tag.TAG_LIST)) {
            ListTag chatList = tag.getList("chatHistory", Tag.TAG_COMPOUND);
            for (int i = 0; i < chatList.size(); i++) {
                chatHistory.add(ChatMessage.load(chatList.getCompound(i)));
            }
        }
    }
}
