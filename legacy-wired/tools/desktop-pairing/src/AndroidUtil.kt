// Desktop-only adapters for the two Android utility calls used by the shared plist/client.
package android.util

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object Xml {
    @JvmStatic fun newPullParser(): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser()
}
object Log {
    @JvmStatic fun i(tag: String, message: String): Int = 0
}
