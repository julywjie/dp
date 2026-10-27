package com.dp.agent.memory;

import java.util.ArrayList;
import java.util.List;

/** 记忆库持久化结构。 */
public class MemoryData {

    private int version = 1;
    private boolean enabled = true;
    private boolean autoExtract = true;
    private List<MemoryEntry> entries = new ArrayList<>();

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAutoExtract() { return autoExtract; }
    public void setAutoExtract(boolean autoExtract) { this.autoExtract = autoExtract; }

    public List<MemoryEntry> getEntries() { return entries; }
    public void setEntries(List<MemoryEntry> entries) { this.entries = entries; }
}
