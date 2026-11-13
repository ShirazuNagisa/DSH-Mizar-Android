package me.shirazu.dshmizar.localagent.poc

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * A plain text screen for the PoC's evidence: the instrumentation test builds a
 * summary and puts it here, then screenshots the activity.
 *
 * Lives in the androidTest source set and is only declared in the androidTest
 * manifest — the real app never sees it.
 */
class PoCResultsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val summary = intent?.getStringExtra(EXTRA_TEXT).orEmpty()

        val textView = TextView(this).apply {
            textSize = 10f
            setTextIsSelectable(true)
            setTextColor(0xFF101010.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(40, 40, 40, 40)
            text = summary
        }
        val white = 0xFFFFFFFF.toInt()
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(white)
            addView(
                textView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        setContentView(scrollView)
    }

    companion object {
        const val EXTRA_TEXT = "me.shirazu.dshmizar.localagent.EVIDENCE_TEXT"
    }
}
