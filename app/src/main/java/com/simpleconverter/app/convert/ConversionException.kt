package com.simpleconverter.app.convert

import java.io.IOException

/** Fehler mit einer Meldung, die direkt so in der App angezeigt werden kann. */
class ConversionException(message: String) : IOException(message)
