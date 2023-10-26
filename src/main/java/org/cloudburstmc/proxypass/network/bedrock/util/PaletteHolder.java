package org.cloudburstmc.proxypass.network.bedrock.util;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaletteHolder {
    private int paletteHeader;
    private int[] words;
    private IntList palette;

    public int getBitsPerBlock() {
        return this.paletteHeader >> 1;
    }

    public boolean isPersistent() {
        return false;
        //return (this.paletteHeader & 0x01) == 0;
    }

    public PaletteHolder copy() {
        int[] words = null;
        if (this.words != null) {
            words = new int[this.words.length];
        }
        return new PaletteHolder(this.paletteHeader, words, new IntArrayList(this.palette));
    }
}