package dev.ukyo.hogetooth

object ReportMap {
    /**
     * Main items
     */
    fun input(size: Int): Byte {
        return (0x80 or size).toByte()
    }

    fun output(size: Int): Byte {
        return (0x90 or size).toByte()
    }

    fun collection(size: Int): Byte {
        return (0xA0 or size).toByte()
    }

    fun feature(size: Int): Byte {
        return (0xB0 or size).toByte()
    }

    fun endCollection(size: Int): Byte {
        return (0xC0 or size).toByte()
    }

    /**
     * Global items
     */
    fun usagePage(size: Int): Byte {
        return (0x04 or size).toByte()
    }

    fun logicalMinimum(size: Int): Byte {
        return (0x14 or size).toByte()
    }

    fun logicalMaximum(size: Int): Byte {
        return (0x24 or size).toByte()
    }

    fun physicalMinimum(size: Int): Byte {
        return (0x34 or size).toByte()
    }

    fun physicalMaximum(size: Int): Byte {
        return (0x44 or size).toByte()
    }

    fun unitExponent(size: Int): Byte {
        return (0x54 or size).toByte()
    }

    fun unit(size: Int): Byte {
        return (0x64 or size).toByte()
    }

    fun reportSize(size: Int): Byte {
        return (0x74 or size).toByte()
    }

    fun reportId(size: Int): Byte {
        return (0x84 or size).toByte()
    }

    fun reportCount(size: Int): Byte {
        return (0x94 or size).toByte()
    }

    /**
     * Local items
     */
    fun usage(size: Int): Byte {
        return (0x08 or size).toByte()
    }

    fun usageMinimum(size: Int): Byte {
        return (0x18 or size).toByte()
    }

    fun usageMaximum(size: Int): Byte {
        return (0x28 or size).toByte()
    }

    fun lsb(value: Int): Byte {
        return (value and 0xFF).toByte()
    }

    fun msb(value: Int): Byte {
        return (value.shr(8) and 0xFF).toByte()
    }
}