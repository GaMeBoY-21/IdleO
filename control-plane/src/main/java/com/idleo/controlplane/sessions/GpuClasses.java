package com.idleo.controlplane.sessions;

import java.util.List;

/**
 * The GPU memory classes renters choose between (ADR 0022): 8, 12, 16 and 24 GB. A GPU sold as 8 GB reports a little
 * under 8,192 MB (an RTX 4060 Laptop reports 8,188, an RTX 4080 Laptop 12,282 for 12 GB), so a class asks for its
 * size in thousands of MB, which every card of the class has.
 */
public final class GpuClasses {

    /** The classes, in GB, smallest first. */
    public static final List<Integer> GB = List.of(8, 12, 16, 24);

    private GpuClasses() {}

    /** What a class asks for, in MB. */
    public static int mb(int gb) {
        return gb * 1_000;
    }
}
