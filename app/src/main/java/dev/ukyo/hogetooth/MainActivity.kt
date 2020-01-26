package dev.ukyo.hogetooth

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var keyboardPeripheral: KeyboardPeripheral

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keyboardPeripheral = KeyboardPeripheral(applicationContext, packageManager)
        val button = findViewById<Button>(R.id.button)
        fun click() = GlobalScope.launch {
            keyboardPeripheral.sendKeys("A")
            Log.i("hello", "hello")
        }
        button?.setOnClickListener { click() }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        keyboardPeripheral.stopAdvertising()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun View.onClick(action: suspend (View) -> Unit) {
        Log.i("hello", "hello")
    }
}
