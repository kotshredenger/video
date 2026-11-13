package com.example.universalplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.universalplayer.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var items: List<String> = emptyList()

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // не все провайдеры поддерживают постоянный доступ
                }
                play(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnOpenFile.setOnClickListener { picker.launch(arrayOf("*/*")) }
        b.btnOpenUrl.setOnClickListener { askUrl() }
        b.btnClear.setOnClickListener {
            Utils.clearHistory(this)
            refresh()
        }

        b.listHistory.setOnItemClickListener { _, _, position, _ ->
            play(Uri.parse(items[position]))
        }
        b.listHistory.setOnItemLongClickListener { _, _, position, _ ->
            MaterialAlertDialogBuilder(this)
                .setTitle("Убрать из списка?")
                .setPositiveButton("Убрать") { _, _ ->
                    Utils.removeHistory(this, items[position])
                    refresh()
                }
                .setNegativeButton("Отмена", null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Utils.history(this)
        val names = items.map { Utils.displayName(this, Uri.parse(it)) }
        b.listHistory.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        b.tvEmpty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/video.mp4"
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ссылка на видео или поток")
            .setView(container)
            .setPositiveButton("Играть") { _, _ ->
                var text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (text.startsWith("/")) text = "file://$text"
                    else if (!text.contains("://")) text = "http://$text"
                    play(Uri.parse(text))
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun play(uri: Uri) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }
}
