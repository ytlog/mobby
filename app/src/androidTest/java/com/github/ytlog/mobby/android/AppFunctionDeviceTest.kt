package com.github.ytlog.mobby.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.appfunctions.AppFunctionCatalog
import com.github.ytlog.mobby.android.appfunctions.AppFunctionListing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the installed mobby package without changing system permissions. */
@RunWith(AndroidJUnit4::class)
class AppFunctionDeviceTest {
    @Test fun android16ReportsActualCallerAccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.github.ytlog.mobby.android", context.packageName)
        assertTrue("Run this test on Android 16 or later", Build.VERSION.SDK_INT >= 36)
        val requested = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("mobby must request EXECUTE_APP_FUNCTIONS",
            Manifest.permission.EXECUTE_APP_FUNCTIONS in requested)
        val catalog = AppFunctionCatalog(context)
        assertNotNull("The App Functions manager must be available", catalog.manager())
        val granted = context.checkSelfPermission(Manifest.permission.EXECUTE_APP_FUNCTIONS) ==
            PackageManager.PERMISSION_GRANTED
        val listing = catalog.list()
        Log.i("MobbyAppFunctionTest", "API=${Build.VERSION.SDK_INT}; manufacturer=${Build.MANUFACTURER}; " +
            "model=${Build.MODEL}; permissionGranted=$granted; listing=$listing")
        if (!granted) {
            assertEquals(AppFunctionListing.Unavailable(AppFunctionListing.Reason.PERMISSION_DENIED), listing)
        } else {
            assertTrue("Unexpected discovery result: $listing", listing is AppFunctionListing.Available ||
                listing == AppFunctionListing.Unavailable(AppFunctionListing.Reason.SYSTEM_DENIED))
        }
    }
}
