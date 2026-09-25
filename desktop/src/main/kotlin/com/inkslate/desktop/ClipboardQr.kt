package com.inkslate.desktop

import java.io.File

/**
 * The clipboard, for QR codes: reading one out of a pasted picture (a screenshot of another
 * device's code, sent through a chat) or plain text, and putting a code's picture on it.
 */
object ClipboardQr {

    /** The clipboard's text, or the QR code in a picture on it - a screenshot pasted from a chat. */
    fun read(): String? = runCatching {
        val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        val flavors = clip.availableDataFlavors.toSet()
        if (java.awt.datatransfer.DataFlavor.imageFlavor in flavors) {
            val image = clip.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
            val buffered = java.awt.image.BufferedImage(image.getWidth(null), image.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
            buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
            val px = buffered.getRGB(0, 0, buffered.width, buffered.height, null, 0, buffered.width)
            com.inkslate.core.QrCodes.decode(buffered.width, buffered.height, px)?.let { return it }
        }
        if (java.awt.datatransfer.DataFlavor.javaFileListFlavor in flavors) {
            @Suppress("UNCHECKED_CAST")
            val files = clip.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<File>
            files.firstNotNullOfOrNull { f ->
                runCatching { javax.imageio.ImageIO.read(f) }.getOrNull()?.let { img ->
                    com.inkslate.core.QrCodes.decode(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
                }
            }?.let { return it }
        }
        if (java.awt.datatransfer.DataFlavor.stringFlavor in flavors) clip.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String else null
    }.getOrNull()

    fun copyImage(png: File): Boolean = runCatching {
        val image = javax.imageio.ImageIO.read(png)
        val selection = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(f: java.awt.datatransfer.DataFlavor) = f == java.awt.datatransfer.DataFlavor.imageFlavor
            override fun getTransferData(f: java.awt.datatransfer.DataFlavor): Any = image
        }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
        true
    }.getOrDefault(false)

    fun writePng(width: Int, height: Int, argb: IntArray, to: File): Boolean = runCatching {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, argb, 0, width)
        to.parentFile?.mkdirs()
        javax.imageio.ImageIO.write(image, "png", to)
    }.getOrDefault(false)
}
