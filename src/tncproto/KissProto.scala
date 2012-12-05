package org.aprsdroid.app

import _root_.android.util.Log
import _root_.java.io.{InputStream, OutputStream}

import _root_.net.ab0oo.aprs.parser._

class KissProto(is : InputStream, os : OutputStream) extends TncProto(is, os) {
	val TAG = "APRSdroid.KissProto"

	object Kiss {
		// escape sequences
		val FEND  = 0xC0
		val FESC  = 0xDB
		val TFEND = 0xDC
		val TFESC = 0xDD

		// commands
		val CMD_DATA = 0x00
	}

	def readPacket() : String = {
		import Kiss._
		val buf = scala.collection.mutable.ListBuffer[Byte]()
		do {
			var ch = is.read()
			Log.d(TAG, "readPacket: %02X '%c'".format(ch, ch))
			ch match {
			case FEND =>
				if (buf.length > 0) {
					Log.d(TAG, "readPacket: sending back %s".format(new String(buf.toArray)))
					try {
						return Parser.parseAX25(buf.toArray).toString().trim()
					} catch {
						case e => buf.clear()
					}
				}
			case FESC => is.read() match {
				case TFEND => buf.append(FEND.toByte)
				case TFESC => buf.append(FESC.toByte)
				case _ =>
				}
			case -1	=> throw new java.io.IOException("KissReader out of data")
			case 0 =>
				// hack: ignore 0x00 byte at start of frame, this is the command
				if (buf.length != 0)
					buf.append(ch.toByte)
				else
					Log.d(TAG, "readPacket: ignoring command byte")
			case _ =>
				buf.append(ch.toByte)
			}
		} while (true)
		""
	}

	def writePacket(p : APRSPacket) {
		Log.d(TAG, "writePacket: " + p)
		os.write(Kiss.FEND)
		os.write(Kiss.CMD_DATA)
		os.write(p.toAX25Frame())
		os.write(Kiss.FEND)
		os.flush()
	}
}
