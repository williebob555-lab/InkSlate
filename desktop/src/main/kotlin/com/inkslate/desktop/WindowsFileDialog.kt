package com.inkslate.desktop

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * The Windows file and folder pickers themselves - the ones File Explorer uses, with its search,
 * its filter, Quick access and the rest - rather than a list drawn by the program.
 *
 * Reached through COM (IFileOpenDialog), since the Java file dialog cannot choose a folder. Each
 * call blocks until the person has chosen, on a thread of its own, owned by the program's window
 * so it opens over it even when that window fills the screen.
 */
object WindowsFileDialog {

    val available: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** A folder, or null if none was chosen. */
    fun folder(title: String, start: File?): File? = show(title, start, emptyList(), folders = true, many = false).firstOrNull()

    /**
     * Files of the kinds in [types] (a name and its patterns, "*.pdf;*.png"), one or [many]; empty
     * if none was chosen.
     */
    fun files(title: String, start: File?, types: List<Pair<String, String>>, many: Boolean = false): List<File> =
        show(title, start, types, folders = false, many = many)

    private interface Shell : Library {
        fun SHCreateItemFromParsingName(path: WString, bindContext: Pointer?, iid: Guid.GUID.ByReference, out: PointerByReference): Int
    }

    private val shell by lazy { Native.load("shell32", Shell::class.java) }

    private val CLSID_FILE_OPEN = Guid.GUID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}")
    private val IID_FILE_OPEN = Guid.GUID("{d57c7288-d4ad-4768-be02-9d969532d960}")
    private val IID_SHELL_ITEM = Guid.GUID("{43826d1e-e718-42ee-bc55-a1e261c37bfe}")

    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val FOS_ALLOWMULTISELECT = 0x200
    private const val FOS_PATHMUSTEXIST = 0x800
    private const val FOS_FILEMUSTEXIST = 0x1000
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    private const val CLSCTX_INPROC_SERVER = 1
    private const val COINIT_APARTMENTTHREADED = 2

    // Places in the interfaces' tables of functions.
    private const val RELEASE = 2
    private const val SHOW = 3
    private const val SET_FILE_TYPES = 4
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_FOLDER = 12
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20
    private const val GET_RESULTS = 27
    private const val ITEM_GET_DISPLAY_NAME = 5
    private const val ARRAY_GET_COUNT = 7
    private const val ARRAY_GET_ITEM_AT = 8

    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val table = obj.getPointer(0)
        val fn = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    private fun show(title: String, start: File?, types: List<Pair<String, String>>, folders: Boolean, many: Boolean): List<File> {
        if (!available) return emptyList()
        var result: List<File> = emptyList()
        val owner = runCatching {
            java.awt.Window.getWindows().firstOrNull { it.isActive } ?: java.awt.Window.getWindows().firstOrNull { it.isShowing }
        }.getOrNull()?.let { runCatching { Native.getWindowPointer(it) }.getOrNull() }
        val worker = Thread({ result = runCatching { showOnThisThread(title, start, types, folders, many, owner) }.getOrDefault(emptyList()) }, "file-picker")
        worker.start()
        worker.join()
        return result
    }

    private fun showOnThisThread(
        title: String, start: File?, types: List<Pair<String, String>>, folders: Boolean, many: Boolean, owner: Pointer?
    ): List<File> {
        Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
        val keep = ArrayList<Memory>()
        fun wide(s: String) = Memory((s.length + 1L) * 2).also { it.setWideString(0, s); keep += it }
        try {
            val out = PointerByReference()
            if (Ole32.INSTANCE.CoCreateInstance(CLSID_FILE_OPEN, null, CLSCTX_INPROC_SERVER, IID_FILE_OPEN, out).toInt() != 0) return emptyList()
            val dialog = out.value
            try {
                val options = IntByReference()
                call(dialog, GET_OPTIONS, options)
                var wanted = options.value or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST
                wanted = if (folders) wanted or FOS_PICKFOLDERS else wanted or FOS_FILEMUSTEXIST
                if (many) wanted = wanted or FOS_ALLOWMULTISELECT
                call(dialog, SET_OPTIONS, wanted)
                call(dialog, SET_TITLE, wide(title))
                if (!folders && types.isNotEmpty()) {
                    val specs = Memory(types.size * 2L * Native.POINTER_SIZE).also { keep += it }
                    types.forEachIndexed { i, (name, pattern) ->
                        specs.setPointer(i * 2L * Native.POINTER_SIZE, wide(name))
                        specs.setPointer((i * 2L + 1) * Native.POINTER_SIZE, wide(pattern))
                    }
                    call(dialog, SET_FILE_TYPES, types.size, specs)
                }
                start?.takeIf { it.isDirectory }?.let { dir ->
                    val item = PointerByReference()
                    if (shell.SHCreateItemFromParsingName(WString(dir.absolutePath), null, Guid.GUID.ByReference(IID_SHELL_ITEM), item) == 0) {
                        call(dialog, SET_FOLDER, item.value)
                        call(item.value, RELEASE)
                    }
                }
                // Anything but S_OK is the person cancelling.
                if (call(dialog, SHOW, owner) != 0) return emptyList()
                return if (many) {
                    val array = PointerByReference()
                    if (call(dialog, GET_RESULTS, array) != 0) return emptyList()
                    try {
                        val count = IntByReference()
                        call(array.value, ARRAY_GET_COUNT, count)
                        (0 until count.value).mapNotNull { i ->
                            val item = PointerByReference()
                            if (call(array.value, ARRAY_GET_ITEM_AT, i, item) != 0) null
                            else try { pathOf(item.value) } finally { call(item.value, RELEASE) }
                        }
                    } finally {
                        call(array.value, RELEASE)
                    }
                } else {
                    val item = PointerByReference()
                    if (call(dialog, GET_RESULT, item) != 0) return emptyList()
                    try { listOfNotNull(pathOf(item.value)) } finally { call(item.value, RELEASE) }
                }
            } finally {
                call(dialog, RELEASE)
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun pathOf(item: Pointer): File? {
        val name = PointerByReference()
        if (call(item, ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name) != 0) return null
        return try { File(name.value.getWideString(0)) } finally { Ole32.INSTANCE.CoTaskMemFree(name.value) }
    }
}
