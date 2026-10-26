package com.inksheets.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `folder contents recover after reopening the library`() {
        val root = tmp.newFolder()
        val lib = Library(LibraryLog(root, "me"))
        val folder = lib.addFolder("Wind Ensemble")
        val child = lib.addFolder("2026", folder.id)
        val set = lib.addSetlist("Concert", folder.id)
        lib.deleteFolder(folder.id)
        assertEquals(null, lib.setlist(set.id)!!.folderId)
        val reopened = Library(LibraryLog(root, "other"))
        assertEquals(folder.id, reopened.deletedFolders().single().first.id)
        reopened.restoreFolder(folder.id)
        assertEquals(folder.id, reopened.setlist(set.id)!!.folderId)
        assertEquals(folder.id, reopened.folders.single { it.id == child.id }.parentId)
        assertTrue(reopened.deletedFolders().isEmpty())
    }

    @Test fun `deleting with contents keeps a persistent full-tree recovery`() {
        val root = tmp.newFolder()
        val lib = Library(LibraryLog(root, "me"))
        val folder = lib.addFolder("Wind Ensemble")
        val child = lib.addFolder("2026", folder.id)
        val set = lib.addSetlist("Concert", child.id)
        lib.rememberFolderContents(folder.id)
        lib.deleteSetlist(set.id); lib.deleteFolder(child.id); lib.deleteFolder(folder.id)
        val reopened = Library(LibraryLog(root, "other"))
        reopened.restoreFolder(folder.id)
        assertEquals(child.id, reopened.setlist(set.id)!!.folderId)
        assertEquals(folder.id, reopened.folders.single { it.id == child.id }.parentId)
        assertTrue(reopened.deletedSetlists().isEmpty())
    }

    @Test fun `restoring an old folder does not reclaim setlists explicitly moved since`() {
        val lib = Library(LibraryLog(tmp.newFolder(), "me"))
        val original = lib.addFolder("Old")
        val newHome = lib.addFolder("New")
        val set = lib.addSetlist("Concert", original.id)
        lib.deleteFolder(original.id)
        lib.editSetlist(set.id) { folderId = newHome.id }
        lib.restoreFolder(original.id)
        assertEquals(newHome.id, lib.setlist(set.id)!!.folderId)
    }
}
