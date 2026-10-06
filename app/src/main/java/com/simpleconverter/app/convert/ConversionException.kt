package com.simpleconverter.app.convert

import androidx.annotation.StringRes
import java.io.IOException

/** Fehler mit einer Meldung für die Nutzer (Text-ID aus strings.xml, wird erst zur Anzeige übersetzt). */
class ConversionException(@StringRes val messageRes: Int, vararg val args: Any) :
    IOException("ConversionException(res=$messageRes)")
