package org.cloudburstmc.proxypass.network.bedrock.util;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PPBlockStorage extends PaletteHolder {
    public static final PPBlockStorage AIR_STORAGE;
    static {
        PPBlockStorage storage = new PPBlockStorage();
        storage.setLegacy(false);
        storage.setEmpty(true);
        AIR_STORAGE = storage;
    }

    private boolean legacy;
    private boolean empty;
    // Legacy data
    private byte[] blockIds;
    private byte[] blockData;
}
