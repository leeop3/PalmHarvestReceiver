package com.palm.harvest.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.palm.harvest.R

class RadioSettingsDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val prefs = requireContext().getSharedPreferences("radio_settings", Context.MODE_PRIVATE)
        val builder = AlertDialog.Builder(requireContext())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_radio_settings, null)

        val editFreq = view.findViewById<EditText>(R.id.editFreq)
        val editTx = view.findViewById<EditText>(R.id.editTx)
        val spinBw = view.findViewById<Spinner>(R.id.spinBw)
        val spinSf = view.findViewById<Spinner>(R.id.spinSf)
        val spinCr = view.findViewById<Spinner>(R.id.spinCr)

        // Set current values
        editFreq.setText(prefs.getInt("freq", 915000000).toString())
        editTx.setText(prefs.getInt("tx", 20).toString())

        builder.setView(view)
            .setTitle("RNode Radio Settings")
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().apply {
                    putInt("freq", editFreq.text.toString().toInt())
                    putInt("tx", editTx.text.toString().toInt())
                    putInt("bw", spinBw.selectedItem.toString().toInt())
                    putInt("sf", spinSf.selectedItem.toString().toInt())
                    putInt("cr", spinCr.selectedItem.toString().toInt())
                    apply()
                }
            }
            .setNegativeButton("Cancel", null)

        return builder.create()
    }
}