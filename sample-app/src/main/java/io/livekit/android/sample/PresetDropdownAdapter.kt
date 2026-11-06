/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.sample

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView

/**
 * Preset dropdown rows grouped under non-selectable [ConnectionPreset.groupLabel] headers.
 */
class PresetDropdownAdapter(
    private val context: Context,
    presets: List<ConnectionPreset>,
) : BaseAdapter(), Filterable {

    sealed class Row {
        data class Header(val label: String) : Row() {
            override fun toString() = label
        }

        data class Entry(val preset: ConnectionPreset) : Row() {
            /** Text written into the collapsed field on selection. */
            override fun toString() = preset.displayText
        }
    }

    /** Groups ordered by first appearance in the config. */
    private val rows: List<Row> = presets.groupBy { it.groupLabel }.flatMap { (label, group) ->
        listOf(Row.Header(label)) + group.map { Row.Entry(it) }
    }

    override fun getCount() = rows.size

    override fun getItem(position: Int): Row = rows[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getViewTypeCount() = 2

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

    override fun areAllItemsEnabled() = false

    override fun isEnabled(position: Int) = rows[position] is Row.Entry

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = (
            convertView ?: LayoutInflater.from(context)
                .inflate(android.R.layout.simple_dropdown_item_1line, parent, false)
            ) as TextView
        val density = context.resources.displayMetrics.density
        when (val row = rows[position]) {
            is Row.Header -> {
                view.text = row.label
                view.setTypeface(null, Typeface.BOLD)
                view.alpha = HEADER_ALPHA
                view.setPaddingRelative((HEADER_PADDING_DP * density).toInt(), view.paddingTop, view.paddingEnd, view.paddingBottom)
            }

            is Row.Entry -> {
                view.text = row.preset.optionText
                view.setTypeface(null, Typeface.NORMAL)
                view.alpha = 1f
                view.setPaddingRelative((ENTRY_PADDING_DP * density).toInt(), view.paddingTop, view.paddingEnd, view.paddingBottom)
            }
        }
        return view
    }

    /** Never narrows the list: the field shows [Row.Entry.toString], which is not a search query. */
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) =
            FilterResults().apply {
                values = rows
                count = rows.size
            }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            notifyDataSetChanged()
        }
    }

    private companion object {
        const val HEADER_ALPHA = 0.6f
        const val HEADER_PADDING_DP = 12
        const val ENTRY_PADDING_DP = 28
    }
}
