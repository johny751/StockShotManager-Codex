package com.stockshot.manager.overlay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.stockshot.indicator.IndicatorMatchResult
import com.stockshot.manager.StockShotApp
import com.stockshot.manager.indicator.IndicatorDetectionOptions
import com.stockshot.manager.indicator.IndicatorSampleLabel
import com.stockshot.manager.indicator.IndicatorSampleSplit
import com.stockshot.manager.indicator.IndicatorSampleStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Private diagnostic screen. Frozen capture, decisions and human labels stay separate. */
class IndicatorReviewActivity : ComponentActivity() {
    private data class Session(val options: IndicatorDetectionOptions, val result: IndicatorMatchResult)
    private var source: Bitmap? = null
    private var session: Session? = null
    private lateinit var image: IndicatorImageView
    private lateinit var report: TextView
    private lateinit var actions: LinearLayout
    private lateinit var sampleStore: IndicatorSampleStore
    private var busy = false
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) work {
            withContext(Dispatchers.IO) {
                val output = contentResolver.openOutputStream(uri) ?: error("无法创建导出文件")
                output.use { sampleStore.exportZip(it) }
            }
            showMessage("已导出样本、人工标签、原检测参数与最近评估报告。")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sampleStore = IndicatorSampleStore(applicationContext)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        column.addView(TextView(this).apply { text = "指标检测依据"; textSize = 21f })
        image = IndicatorImageView(this)
        column.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val scroll = ScrollView(this)
        report = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); text = "正在读取检测结果…" }
        scroll.addView(report)
        column.addView(scroll, LinearLayout.LayoutParams(-1, dp(190)))
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this)
        val toggle = CheckBox(this).apply { text = "显示标注"; isChecked = true; setOnCheckedChangeListener { _, checked -> image.annotations = checked } }
        row.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "返回行情"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(row)
        val row2 = LinearLayout(this)
        row2.addView(Button(this).apply { text = "保存人工标注"; setOnClickListener { labelSample() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(Button(this).apply { text = "评估样本库"; setOnClickListener { evaluateSamples() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(row2)
        actions.addView(Button(this).apply { text = "导出样本与评估报告 ZIP"; setOnClickListener { export.launch("StockShotManager-indicator-samples.zip") } })
        column.addView(actions)
        setContentView(column)
        val id = intent.getStringExtra(EXTRA_SESSION)
        if (id == null) {
            image.visibility = View.GONE
            report.text = "样本库按调参与独立验收分组。评估使用当前已保存的识别设置。没有人工标签时不计算正确率；无法判断单独计数。请先在行情页使用“指标识别”保存真实样本。"
        } else work {
            val loaded = withContext(Dispatchers.IO) {
                require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "无效检测会话" }
                val folder = File(cacheDir, "indicator-review/$id")
                val entry = Gson().fromJson(File(folder, "session.json").readText(), Session::class.java)
                val bitmap = BitmapFactory.decodeFile(File(folder, "source.png").absolutePath) ?: error("截图已清理，请重新检测")
                entry to bitmap
            }
            session = loaded.first; source = loaded.second
            image.bitmap = loaded.second; image.result = loaded.first.result
            report.text = IndicatorReport.describe(loaded.first.result)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; enableActions(false)
        lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { showMessage("操作失败：" + (e.message ?: e.javaClass.simpleName)) }
            finally { busy = false; enableActions(true) }
        }
    }
    private fun enableActions(enabled: Boolean) {
        fun set(v: View) { v.isEnabled = enabled; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) set(v.getChildAt(i)) }
        if (::actions.isInitialized) set(actions)
    }
    private fun showMessage(text: String) { if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setMessage(text).setPositiveButton("确定", null).show() }
    private fun labelSample() {
        if (busy) return
        val entry = session ?: return showMessage("请先在行情页执行指标识别。")
        val bitmap = source ?: return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        form.addView(TextView(this).apply { text = "人工标签独立于程序结果。请按本次所选资金规则核对；边界样本单独统计。" })
        val labels = Spinner(this).apply { adapter = ArrayAdapter(this@IndicatorReviewActivity, android.R.layout.simple_spinner_dropdown_item, listOf("请选择人工结论", "符合", "不符合", "边界 / 无法确认")) }
        val splits = Spinner(this).apply { adapter = ArrayAdapter(this@IndicatorReviewActivity, android.R.layout.simple_spinner_dropdown_item, listOf("调参样本", "独立验收样本")) }
        form.addView(labels); form.addView(splits)
        form.addView(TextView(this).apply { text = "用于调参的图片不能再计入独立验收。同一截图不能跨组重复保存。标签和分组保存后固定；更换判断规则后请重新核对标签适用性。"; textSize = 12f })
        val note = EditText(this).apply { hint = "备注：股票代码、信号位置、判断依据"; maxLines = 3 }
        form.addView(note)
        val dialog = AlertDialog.Builder(this).setTitle("保存真实样本").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = when (labels.selectedItemPosition) {
                    1 -> IndicatorSampleLabel.MATCH
                    2 -> IndicatorSampleLabel.NO_MATCH
                    3 -> IndicatorSampleLabel.BOUNDARY
                    else -> { showMessage("请先选择人工结论。"); return@setOnClickListener }
                }
                val split = if (splits.selectedItemPosition == 1) IndicatorSampleSplit.HOLDOUT else IndicatorSampleSplit.TUNING
                val noteText = note.text.toString()
                dialog.dismiss()
                work {
                    val sample = withContext(Dispatchers.IO) { sampleStore.save(bitmap, entry.options, entry.result, label, split, noteText) }
                    showMessage("样本已保存：" + sample.id.take(12) + "。原图、参数、检测结果及人工标签已保留。")
                }
            }
        }
        dialog.show()
    }
    private fun evaluateSamples() = work {
        val options = IndicatorDetectionOptions.fromSettings((application as StockShotApp).settingsStore)
        val result = withContext(Dispatchers.Default) { sampleStore.evaluate(options) }
        val text = buildString {
            append("使用当前已保存设置重新评估。历史原结果保持不变。\n")
            append("人工标签按保存时规则确认；更换方案/规则后须复核适用性。\n\n")
            fun group(name: String, total: Int, reviewed: Int, tp: Int, fp: Int, tn: Int, fn: Int, unknown: Int, boundary: Int, unlabeled: Int) {
                append(name).append("：共 ").append(total).append("，已人工标注 ").append(reviewed).append('\n')
                append("正确命中 ").append(tp).append("；误报 ").append(fp).append("；正确排除 ").append(tn).append("；漏检 ").append(fn).append('\n')
                append("无法判断 ").append(unknown).append("；边界 ").append(boundary).append("；未标注 ").append(unlabeled).append("\n\n")
            }
            val a = result.tuning; val b = result.holdout
            group("调参", a.total, a.reviewed, a.tp, a.fp, a.tn, a.fn, a.unknown, a.boundary, a.unlabeled)
            group("独立验收", b.total, b.reviewed, b.tp, b.fp, b.tn, b.fn, b.unknown, b.boundary, b.unlabeled)
            append("本次可计分标签：调参 ").append(a.binaryLabeled).append("，独立验收 ").append(b.binaryLabeled).append("\n")
            append("标签规则不同，已排除计分：调参 ").append(a.incompatibleLabels).append("，独立验收 ").append(b.incompatibleLabels).append("\n\n")
            if (b.total == 0) append("尚无独立验收样本，不能据此报告泛化识别率。\n")
            result.rows.forEach { row -> append(row.sampleId.take(12)).append("：").append(IndicatorReport.label(row.status)).append(row.error?.let { " / $it" } ?: "").append('\n') }
            append("\n完整参数、逐样本结果可通过 ZIP 导出。")
        }
        report.text = text
    }
    override fun onDestroy() {
        image.bitmap = null
        // A coroutine writing the original bitmap may still be completing cancellation; let GC own recycling.
        source = null
        super.onDestroy()
    }
    companion object {
        private const val EXTRA_SESSION = "indicator_session"
        suspend fun open(context: Context, bitmap: Bitmap, options: IndicatorDetectionOptions, result: IndicatorMatchResult) {
            val id = UUID.randomUUID().toString()
            withContext(Dispatchers.IO) {
                val folder = File(context.cacheDir, "indicator-review/$id")
                check(folder.mkdirs()) { "无法保存检测截图" }
                try {
                    File(folder, "source.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法保存检测截图" } }
                    File(folder, "session.json").writeText(Gson().toJson(Session(options, result)))
                } catch (e: Exception) {
                    File(folder, "source.png").delete()
                    File(folder, "session.json").delete()
                    folder.delete()
                    throw e
                }
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(context, IndicatorReviewActivity::class.java).putExtra(EXTRA_SESSION, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        fun openLibrary(context: Context) {
            context.startActivity(Intent(context, IndicatorReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
