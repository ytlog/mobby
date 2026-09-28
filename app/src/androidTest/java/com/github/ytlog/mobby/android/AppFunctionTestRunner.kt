package com.github.ytlog.mobby.android

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Test Lab's web console has no test-class filter; this runner selects the device probe. */
class AppFunctionTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val selected = Bundle(arguments ?: Bundle())
        selected.putString("class", AppFunctionDeviceTest::class.java.name)
        super.onCreate(selected)
    }
}
