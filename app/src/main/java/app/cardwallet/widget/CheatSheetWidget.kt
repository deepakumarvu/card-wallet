package app.cardwallet.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.cardwallet.Cashback
import app.cardwallet.MainActivity
import app.cardwallet.WalletDb
import app.cardwallet.cardGradient
import app.cardwallet.rupees

/** Home-screen cheat sheet: the best card right now for each kind of spend (₹1,000), caps counted. Tap to open the app. */
class CheatSheetWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = WalletDb.get(context).dao()
        val sheet = Cashback.cheatSheet(1000_00, dao.allCards(), dao.allRules(), dao.allSpends(), dao.allMerchants())
        val white = ColorProvider(Color(0xFFF4F1EA))
        val grey = ColorProvider(Color(0xFF8F8C86))
        val gold = ColorProvider(Color(0xFFE3C38C))
        provideContent {
            Column(
                GlanceModifier.fillMaxSize().background(Color(0xFF0A0A0C)).cornerRadius(24.dp).padding(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Text("WHICH CARD · ₹1,000", style = TextStyle(color = grey, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(8.dp))
                sheet.forEach { (tile, pick) ->
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tile, style = TextStyle(color = grey, fontSize = 12.sp), modifier = GlanceModifier.width(84.dp))
                        if (pick == null) Text("—", style = TextStyle(color = grey, fontSize = 12.sp))
                        else {
                            Box(GlanceModifier.size(14.dp, 10.dp).background(cardGradient(pick.card).last()).cornerRadius(2.dp)) {}
                            Spacer(GlanceModifier.width(6.dp))
                            Text(pick.card.name, maxLines = 1, style = TextStyle(color = white, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                modifier = GlanceModifier.defaultWeight())
                            Text(rupees(pick.cashbackPaise), style = TextStyle(color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}

class CheatSheetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CheatSheetWidget()
}
