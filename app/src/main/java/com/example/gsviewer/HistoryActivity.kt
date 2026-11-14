package com.example.gsviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Videos watched during the last 30 days. Tap = watch again, long press = remove from history. */
class HistoryActivity : Activity() {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var clearButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        recycler = findViewById(R.id.history_recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        emptyView = findViewById(R.id.history_empty)
        clearButton = findViewById(R.id.btn_clear_history)

        clearButton.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val items = HistoryStore.getHistory(this)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        clearButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        recycler.adapter = HistoryAdapter(
            items = items,
            onClick = { entry -> openVideo(entry) },
            onLongClick = { entry -> confirmRemove(entry) }
        )
    }

    private fun openVideo(entry: HistoryEntry) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_url", entry.url)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun confirmRemove(entry: HistoryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Հեռացնել պատմությունից")
            .setMessage(entry.title)
            .setPositiveButton("Հեռացնել") { _, _ ->
                HistoryStore.remove(this, entry.videoId)
                refreshList()
            }
            .setNegativeButton("Չեղարկել", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Մաքրել պատմությունը")
            .setMessage("Համոզված՞ եք, որ ուզում եք ջնջել դիտումների ողջ պատմությունը:")
            .setPositiveButton("Այո") { _, _ ->
                HistoryStore.clear(this)
                refreshList()
            }
            .setNegativeButton("Ոչ", null)
            .show()
    }
}
