package com.example.gsviewer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Every dialog of the app is a modern bottom sheet built here (one look everywhere). */
object Sheets {

    class Action(
        val label: String,
        val iconRes: Int,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    private enum class ButtonKind { PRIMARY, DANGER, TEXT }

    // ------------------------------------------------------------------ building blocks

    private fun text(c: Context, value: String, sp: Float, colorRes: Int, medium: Boolean = false): TextView =
        TextView(c).apply {
            text = value
            textSize = sp
            setTextColor(c.getColor(colorRes))
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun newSheet(activity: Activity, title: String?, subtitle: String?): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(activity, R.style.GS_Sheet)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.bg_sheet)
            setPadding(Ui.dp(activity, 20), Ui.dp(activity, 10), Ui.dp(activity, 20), Ui.dp(activity, 24))
        }
        root.addView(View(activity).apply {
            background = activity.getDrawable(R.drawable.bg_handle)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(activity, 40), Ui.dp(activity, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = Ui.dp(activity, 16)
            }
        })
        if (title != null) {
            root.addView(text(activity, title, 20f, R.color.gs_text, medium = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        if (subtitle != null) {
            root.addView(text(activity, subtitle, 13f, R.color.gs_text_secondary).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, Ui.dp(activity, 4), 0, 0)
            })
        }
        if (title != null) {
            root.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(1, Ui.dp(activity, 14))
            })
        }
        return Pair(dialog, root)
    }

    private fun present(activity: Activity, dialog: Dialog, root: View, keyboard: Boolean = false) {
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            if (keyboard) {
                setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
            }
        }
        dialog.setCanceledOnTouchOutside(true)
        if (!activity.isFinishing && !activity.isDestroyed) dialog.show()
    }

    private fun row(
        c: Context,
        iconRes: Int,
        circleBg: Int,
        iconTint: Int,
        title: String,
        meta: String?,
        titleColor: Int,
        trailingIconRes: Int?,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = c.getDrawable(R.drawable.bg_row)
            setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(c).apply {
            setImageResource(iconRes)
            setColorFilter(c.getColor(iconTint))
            scaleType = ImageView.ScaleType.CENTER
            background = c.getDrawable(circleBg)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40))
        })
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(c, 14)
            }
        }
        texts.addView(text(c, title, 15f, titleColor, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (meta != null) {
            texts.addView(text(c, meta, 12f, R.color.gs_text_secondary).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(texts)
        if (trailingIconRes != null) {
            row.addView(ImageView(c).apply {
                setImageResource(trailingIconRes)
                setColorFilter(c.getColor(R.color.gs_accent_light))
                layoutParams = LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)).apply {
                    marginStart = Ui.dp(c, 8)
                }
            })
        }
        return row
    }

    private fun button(c: Context, label: String, kind: ButtonKind, onClick: () -> Unit): TextView =
        TextView(c).apply {
            text = label
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = Ui.dp(c, 48)
            setPadding(Ui.dp(c, 24), 0, Ui.dp(c, 24), 0)
            when (kind) {
                ButtonKind.PRIMARY -> {
                    setTextColor(c.getColor(R.color.gs_white))
                    background = c.getDrawable(R.drawable.bg_button_primary)
                }
                ButtonKind.DANGER -> {
                    setTextColor(c.getColor(R.color.gs_white))
                    background = c.getDrawable(R.drawable.bg_button_danger)
                }
                ButtonKind.TEXT -> {
                    setTextColor(c.getColor(R.color.gs_text_secondary))
                    background = c.getDrawable(R.drawable.bg_button_text)
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun buttonRow(c: Context, vararg buttons: View): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, Ui.dp(c, 20), 0, 0)
            buttons.forEachIndexed { index, b ->
                addView(b, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) marginStart = Ui.dp(c, 8) })
            }
        }

    // ------------------------------------------------------------------ public sheets

    /** A list of actions (rename, delete, ...). */
    fun actions(activity: Activity, title: String, subtitle: String?, actions: List<Action>) {
        val (dialog, root) = newSheet(activity, title, subtitle)
        actions.forEach { a ->
            root.addView(
                row(
                    activity, a.iconRes, R.drawable.bg_circle_soft,
                    if (a.destructive) R.color.gs_danger else R.color.gs_icon,
                    a.label, null,
                    if (a.destructive) R.color.gs_danger else R.color.gs_text,
                    null
                ) {
                    dialog.dismiss()
                    a.onClick()
                }
            )
        }
        present(activity, dialog, root)
    }

    /** Are you sure? */
    fun confirm(
        activity: Activity,
        title: String,
        message: String?,
        confirmLabel: String,
        destructive: Boolean,
        onConfirm: () -> Unit
    ) {
        val (dialog, root) = newSheet(activity, title, null)
        if (message != null) {
            root.addView(text(activity, message, 14f, R.color.gs_text_secondary).apply {
                setLineSpacing(0f, 1.15f)
            })
        }
        root.addView(
            buttonRow(
                activity,
                button(activity, activity.getString(R.string.cancel), ButtonKind.TEXT) { dialog.dismiss() },
                button(
                    activity, confirmLabel,
                    if (destructive) ButtonKind.DANGER else ButtonKind.PRIMARY
                ) {
                    dialog.dismiss()
                    onConfirm()
                }
            )
        )
        present(activity, dialog, root)
    }

    /** One line of text (playlist name). */
    fun input(
        activity: Activity,
        title: String,
        hint: String,
        initial: String,
        confirmLabel: String,
        onConfirm: (String) -> Unit
    ) {
        val (dialog, root) = newSheet(activity, title, null)
        val field = EditText(activity).apply {
            setText(initial)
            this.hint = hint
            setHintTextColor(activity.getColor(R.color.gs_text_tertiary))
            setTextColor(activity.getColor(R.color.gs_text))
            textSize = 16f
            background = activity.getDrawable(R.drawable.bg_input)
            setPadding(Ui.dp(activity, 16), Ui.dp(activity, 14), Ui.dp(activity, 16), Ui.dp(activity, 14))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
        }
        val submit = {
            val value = field.text.toString().trim()
            if (value.isNotEmpty()) {
                dialog.dismiss()
                onConfirm(value)
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        root.addView(field, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(
            buttonRow(
                activity,
                button(activity, activity.getString(R.string.cancel), ButtonKind.TEXT) { dialog.dismiss() },
                button(activity, confirmLabel, ButtonKind.PRIMARY) { submit() }
            )
        )
        present(activity, dialog, root, keyboard = true)
        field.requestFocus()
    }

    /** Choose a playlist for a video (or create a new one). */
    fun playlistPicker(
        activity: Activity,
        video: SavedVideo,
        playlists: List<Playlist>,
        onPick: (Playlist) -> Unit,
        onNew: () -> Unit
    ) {
        val (dialog, root) = newSheet(activity, activity.getString(R.string.add_to_playlist), video.title)
        root.addView(
            row(
                activity, R.drawable.ic_add, R.drawable.bg_circle_accent, R.color.gs_white,
                activity.getString(R.string.new_playlist), null, R.color.gs_accent_light, null
            ) {
                dialog.dismiss()
                onNew()
            }
        )
        playlists.forEach { playlist ->
            val count = playlist.videos?.size ?: 0
            val already = playlist.videos?.any { it.videoId == video.videoId } == true
            root.addView(
                row(
                    activity, R.drawable.ic_library, R.drawable.bg_circle_soft, R.color.gs_icon,
                    playlist.name,
                    activity.resources.getQuantityString(R.plurals.n_videos, count, count),
                    R.color.gs_text,
                    if (already) R.drawable.ic_check else null
                ) {
                    dialog.dismiss()
                    onPick(playlist)
                }
            )
        }
        present(activity, dialog, root)
    }
}
