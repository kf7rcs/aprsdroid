package org.aprsdroid.app

import _root_.android.app.Activity
import _root_.android.app.AlertDialog
import _root_.android.content._
import _root_.android.content.pm.PackageInfo;
import _root_.android.location._
import _root_.android.net.Uri
import _root_.android.os.{Bundle, Handler}
import _root_.android.preference.PreferenceManager
import _root_.java.text.SimpleDateFormat
import _root_.android.util.Log
import _root_.android.view.{LayoutInflater, Menu, MenuItem, View}
import _root_.android.view.View.OnClickListener
import _root_.android.widget.AdapterView
import _root_.android.widget.AdapterView.OnItemClickListener
import _root_.android.widget.Button
import _root_.android.widget.{ListView,SimpleCursorAdapter}
import _root_.android.widget.TextView
import _root_.android.widget.Toast
import _root_.java.util.Date

class APRSdroid extends Activity with OnClickListener
		with DialogInterface.OnClickListener {
	val TAG = "APRSdroid"

	lazy val prefs = new PrefsWrapper(this)
	lazy val storage = StorageDatabase.open(this)
	lazy val postcursor = storage.getPosts("100")

	lazy val postlist = findViewById(R.id.postlist).asInstanceOf[ListView]

	lazy val singleBtn = findViewById(R.id.singlebtn).asInstanceOf[Button]
	lazy val startstopBtn = findViewById(R.id.startstopbtn).asInstanceOf[Button]

	lazy val locReceiver = new LocationReceiver(new Handler(), () => {
			Benchmark("requery") { postcursor.requery() }
			//postlist.setSelection(0)
			setupButtons(AprsService.running)
		})

	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)

		Log.d(TAG, "starting " + getString(R.string.build_version))

		singleBtn.setOnClickListener(this);
		startstopBtn.setOnClickListener(this);

		registerReceiver(locReceiver, new IntentFilter(AprsService.UPDATE))

		startManagingCursor(postcursor)
		val la = new SimpleCursorAdapter(this, R.layout.listitem, 
				postcursor,
				Array("TSS", StorageDatabase.Post.STATUS, StorageDatabase.Post.MESSAGE),
				Array(R.id.listts, R.id.liststatus, R.id.listmessage))
		la.setViewBinder(new PostViewBinder())
		la.setFilterQueryProvider(storage.getPostFilter("100"))
		postlist.setAdapter(la)
		postlist.setTextFilterEnabled(true)
		postlist.setOnItemClickListener(new OnItemClickListener() {
			override def onItemClick(parent : AdapterView[_], view : View, position : Int, id : Long) {
				// When clicked, show a toast with the TextView text
				val (ts, status, message) = storage.getSinglePost("_ID = ?", Array(id.toString()))
				Log.d(TAG, "onItemClick: %s: %s".format(status, message))
				if (status != null) {
					// extract call sign without ssid
					val filter = message.split(">")(0).split("-")(0)
					postlist.setFilterText(filter)
				}
			}
		});

	}

	override def onResume() {
		super.onResume()
		if (prefs.getBoolean("firstrun", true)) {
			new AlertDialog.Builder(this).setTitle(getString(R.string.fr_title))
				.setMessage(getString(R.string.fr_text))
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setPositiveButton(android.R.string.ok, this)
				.setNegativeButton(android.R.string.cancel, this)
				.create.show
			return
		}
		if (!checkConfig())
			return
		val callsign = prefs.getCallsign()
		val callssid = AprsPacket.formatCallSsid(callsign, prefs.getString("ssid", ""))
		setTitle(getString(R.string.app_name) + ": " + callssid)
		setupButtons(AprsService.running)
	}

	override def onDestroy() {
		super.onDestroy()
		unregisterReceiver(locReceiver)
	}

	override def onCreateOptionsMenu(menu : Menu) : Boolean = {
		getMenuInflater().inflate(R.menu.options, menu);
		true
	}

	def openPrefs(toastId : Int) {
		startActivity(new Intent(this, classOf[PrefsAct]));
		Toast.makeText(this, toastId, Toast.LENGTH_SHORT).show()
	}

	def passcodeWarning(call : String, pass : String) {
		import Backend._
		if ((defaultBackendInfo(prefs).need_passcode == PASSCODE_OPTIONAL) &&
				!AprsPacket.passcodeAllowed(call, pass, false))
			Toast.makeText(this, R.string.anon_warning, Toast.LENGTH_LONG).show()
	}

	def passcodeConfigRequired(call : String, pass : String) : Boolean = {
		import Backend._
		// a valid passcode must be entered for "required",
		// "" and "-1" are accepted as well for "optional"
		defaultBackendInfo(prefs).need_passcode match {
		case PASSCODE_NONE => false
		case PASSCODE_OPTIONAL =>
			!AprsPacket.passcodeAllowed(call, pass, true)
		case PASSCODE_REQUIRED =>
			!AprsPacket.passcodeAllowed(call, pass, false)
		}
	}

	def checkConfig() : Boolean = {
		val callsign = prefs.getCallsign()
		val passcode = prefs.getPasscode()
		if (callsign == "") {
			openPrefs(R.string.firstrun)
			return false
		}
		if (passcodeConfigRequired(callsign, passcode)) {
			openPrefs(R.string.wrongpasscode)
			return false
		} else passcodeWarning(callsign, passcode)

		if (prefs.getStringInt("interval", 10) < 1) {
			openPrefs(R.string.mininterval)
			return false
		}
		true
	}

	def setupButtons(running : Boolean) {
		singleBtn.setEnabled(!running)
		if (running) {
			startstopBtn.setText(R.string.stoplog)
		} else {
			startstopBtn.setText(R.string.startlog)
		}
	}

	def aboutDialog() {
		val pi = getPackageManager().getPackageInfo(getPackageName(), 0)
		val title = getString(R.string.ad_title, pi.versionName);
		val inflater = getLayoutInflater()
		val aboutview = inflater.inflate(R.layout.aboutview, null)
		new AlertDialog.Builder(this).setTitle(title)
			.setView(aboutview)
			.setIcon(android.R.drawable.ic_dialog_info)
			.setPositiveButton(android.R.string.ok, null)
			.setNeutralButton(R.string.ad_homepage, new UrlOpener(this, "http://aprsdroid.org/"))
			.create.show
	}

	override def onOptionsItemSelected(mi : MenuItem) : Boolean = {
		mi.getItemId match {
		case R.id.preferences =>
			startActivity(new Intent(this, classOf[PrefsAct]));
			true
		case R.id.clear =>
			storage.trimPosts(System.currentTimeMillis)
			postcursor.requery()
			true
		case R.id.about =>
			aboutDialog()
			true
		case R.id.map =>
			startActivity(new Intent(this, classOf[MapAct]));
			true
		case R.id.quit =>
			stopService(AprsService.intent(this, AprsService.SERVICE))
			finish();
			true
		case _ => false
		}
	}

	override def onClick(d : DialogInterface, which : Int) {
		which match {
		case DialogInterface.BUTTON_POSITIVE =>
			prefs.prefs.edit().putBoolean("firstrun", false).commit();
			checkConfig()
		case _ =>
			finish()
		}
	}

	override def onClick(view : View) {
		Log.d(TAG, "onClick: " + view + "/" + view.getId)

		view.getId match {
		case R.id.singlebtn =>
			passcodeWarning(prefs.getCallsign(), prefs.getPasscode())
			startActivity(new Intent(this, classOf[StationActivity]));
			//startService(AprsService.intent(this, AprsService.SERVICE_ONCE))
		case R.id.startstopbtn =>
			val is_running = AprsService.running
			if (!is_running) {
				startService(AprsService.intent(this, AprsService.SERVICE))
			} else {
				stopService(AprsService.intent(this, AprsService.SERVICE))
			}
			setupButtons(!is_running)
		case _ =>
			//status.setText(view.asInstanceOf[Button].getText)
		}
	}
}

class UrlOpener(ctx : Context, url : String) extends DialogInterface.OnClickListener {
	override def onClick(d : DialogInterface, which : Int) {
		ctx.startActivity(new Intent(Intent.ACTION_VIEW,
			Uri.parse(url)))
	}
}

