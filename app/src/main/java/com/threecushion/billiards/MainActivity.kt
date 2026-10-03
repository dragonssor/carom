package com.threecushion.billiards

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.threecushion.billiards.ui.BilliardsView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(BilliardsView(this))
    }
}
