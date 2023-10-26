package org.cloudburstmc.proxypass.network.bedrock.util;

import lombok.Data;

@Data
public class SubChunkHolder {
    private final int y;
    private final PPBlockStorage[] storages;

    public static SubChunkHolder emptyHolder(int y) {
        return new SubChunkHolder(y, new PPBlockStorage[]{PPBlockStorage.AIR_STORAGE, PPBlockStorage.AIR_STORAGE});
    }
}
