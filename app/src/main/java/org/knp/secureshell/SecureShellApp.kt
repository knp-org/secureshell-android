package org.knp.secureshell

import android.app.Application
import org.knp.secureshell.data.db.AppDatabase
import org.knp.secureshell.data.repository.AppRepository
import org.knp.secureshell.ssh.SshSessionManager
import org.knp.secureshell.sync.LanSyncManager

class SecureShellApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var repository: AppRepository
        private set
    lateinit var sshManager: SshSessionManager
        private set
    lateinit var syncManager: LanSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        repository = AppRepository(database)
        sshManager = SshSessionManager(applicationContext)
        syncManager = LanSyncManager(repository, applicationContext)
    }
}
