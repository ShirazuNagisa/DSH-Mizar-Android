package me.shirazu.dshmizar.ui

import android.content.Context
import me.shirazu.dshmizar.R

/**
 * The operator's names for the host's tool identifiers.
 *
 * An approval carries an identifier, not a name: `write`, `edit`, `shell`. Those
 * are protocol data and have no localised form on the wire, so a card asking for
 * permission renders them as a foreign word inside an otherwise Chinese sentence.
 * The table is deliberately closed, like iOS `MizarKit/ToolNames.swift`: an
 * identifier from a build this app has not met is shown exactly as the host wrote
 * it rather than guessed at.
 */
fun toolDisplayName(context: Context, identifier: String): String = when (identifier.lowercase()) {
    // The file-system tools DSH registers. `write_file` and `read_file` are older
    // spellings still used by hosts and fixtures in the field.
    "read", "read_file", "readfile" -> context.getString(R.string.tool_read_file)
    "read_image", "readimage" -> context.getString(R.string.tool_read_image)
    "write", "write_file", "writefile" -> context.getString(R.string.tool_write_file)
    "edit", "edit_file", "editfile", "multiedit" -> context.getString(R.string.tool_edit_file)

    // A command the agent wants to run.
    "shell", "bash", "execute", "exec", "run", "run_command" -> context.getString(R.string.tool_shell)

    // Search and the network.
    "glob", "find", "list_dir", "list_files", "ls" -> context.getString(R.string.tool_find_files)
    "grep", "search", "search_files" -> context.getString(R.string.tool_search_contents)
    "web_search", "websearch" -> context.getString(R.string.tool_web_search)
    "web_fetch", "webfetch" -> context.getString(R.string.tool_web_fetch)

    // Everything else DSH exposes to a model.
    "ask_user_question", "ask_user", "question" -> context.getString(R.string.tool_ask_operator)
    "todo_write", "todowrite", "todo" -> context.getString(R.string.tool_update_tasks)
    "task", "subagent" -> context.getString(R.string.tool_subagent_task)
    "apply_patch", "patch" -> context.getString(R.string.tool_apply_patch)

    else -> identifier
}
