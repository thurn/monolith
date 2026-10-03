## Hot-seat 3D table for the Jinteki.net rules engine. The engine runs in a JVM sidecar;
## this script only renders the per-side views it returns and sends player actions back.
extends Node3D

const IMG_URL := "https://card-images.netrunnerdb.com/v2/large/%s.jpg"
const IMG_DIR := "user://cards"
const COL_STEP := 1.4

var sidecar: Sidecar
var views := {}
var viewing := "corp"
var manual_view := false
var cards := {}  # key -> CardView
var textures := {}  # code -> Texture2D (null while downloading)
var args := {}
var autoplay: Array[Callable] = []
var images := true

var status_label: Label
var perf_label: Label
var prompt_box: VBoxContainer
var actions_box: HFlowContainer
var log_label: RichTextLabel
var preview: TextureRect
var preview_text: Label
var perf := {"boot": 0, "rtt": 0.0, "engine": 0.0, "bytes": 0}


func _ready() -> void:
	for a in OS.get_cmdline_user_args():
		var kv := a.trim_prefix("--").split("=", true, 1)
		args[kv[0]] = kv[1] if kv.size() > 1 else true
	images = not args.has("no-images") and DisplayServer.get_name() != "headless"
	DirAccess.make_dir_recursive_absolute(IMG_DIR)
	get_viewport().physics_object_picking = true
	_build_world()
	_build_ui()
	sidecar = Sidecar.new()
	add_child(sidecar)
	sidecar.message.connect(_on_message)
	sidecar.died.connect(func(): status_label.text = "Sidecar process exited.")
	if sidecar.start() != OK:
		status_label.text = "Failed to start sidecar (see console)."
		if args.has("smoke"):
			get_tree().quit(1)
	if args.has("autoplay"):
		_queue_autoplay()


# --- Sidecar protocol -------------------------------------------------------

func act(side: String, command: String, a := {}) -> void:
	sidecar.send({"op": "action", "side": side, "command": command, "args": a})


func choose(side: String, value: String) -> void:
	var ps: Dictionary = _prompt(side)
	for c in _arr(ps.get("choices")):
		if c is Dictionary and str(c.get("value")) == value:
			act(side, "choice", {"choice": {"uuid": c["uuid"]}, "eid": ps.get("eid")})
			return
	push_warning("No choice %s for %s" % [value, side])


func _on_message(msg: Dictionary) -> void:
	if msg.get("op") == "ready":
		perf.boot = Time.get_ticks_msec() - sidecar.launch_ms
		print("sidecar ready: %d ms after launch (cards loaded in %d ms)" % [perf.boot, msg["boot-ms"]])
		sidecar.send({"op": "new-game"})
		return
	perf.rtt = sidecar.rtt_ms(int(msg.get("id", 0)))
	perf.engine = msg.get("ms", 0.0)
	if not msg.get("ok", false):
		_append_error(str(msg.get("error")))
		return
	views = {"corp": msg["corp"], "runner": msg["runner"]}
	print("response id=%d engine=%.1fms rtt=%.1fms" % [msg["id"], perf.engine, perf.rtt])
	if args.has("smoke"):
		get_tree().quit(0 if views.corp.has("corp") else 1)
		return
	_auto_view()
	_render()
	if not autoplay.is_empty():
		get_tree().create_timer(0.6).timeout.connect(_autoplay_step)


func _prompt(side: String) -> Dictionary:
	if views.is_empty():
		return {}
	var ps = views[side][side].get("prompt-state")
	return ps if ps is Dictionary else {}


func _other(side: String) -> String:
	return "runner" if side == "corp" else "corp"


func _auto_view() -> void:
	if manual_view:
		return
	var active: String = views.corp.get("active-player", "corp")
	for s in [active, _other(active)]:
		var ps := _prompt(s)
		if not ps.is_empty() and ps.get("prompt-type") != "run":
			viewing = s
			return
	viewing = active


# --- World ------------------------------------------------------------------

func _build_world() -> void:
	var env := WorldEnvironment.new()
	env.environment = Environment.new()
	env.environment.background_mode = Environment.BG_COLOR
	env.environment.background_color = Color(0.05, 0.06, 0.08)
	env.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.environment.ambient_light_color = Color(0.5, 0.5, 0.55)
	env.environment.ambient_light_energy = 0.6
	env.environment.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	add_child(env)

	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-60, 25, 0)
	sun.light_energy = 1.1
	sun.shadow_enabled = true
	add_child(sun)

	var table := MeshInstance3D.new()
	var plane := PlaneMesh.new()
	plane.size = Vector2(16, 12)
	table.mesh = plane
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.08, 0.20, 0.16)
	mat.roughness = 0.9
	table.material_override = mat
	table.position.y = -0.02
	add_child(table)

	# A thin line dividing the Corp half (far) from the Runner half (near).
	var divider := MeshInstance3D.new()
	var bar := BoxMesh.new()
	bar.size = Vector3(15, 0.005, 0.03)
	divider.mesh = bar
	var dmat := StandardMaterial3D.new()
	dmat.albedo_color = Color(0.3, 0.6, 0.5)
	divider.material_override = dmat
	divider.position = Vector3(0, -0.01, 0.15)
	add_child(divider)

	var cam := Camera3D.new()
	# Offset right so the board centers in the area left of the HUD's side panel.
	cam.position = Vector3(1.25, 9.2, 5.6)
	cam.fov = 50
	add_child(cam)
	cam.look_at(Vector3(1.25, 0, 0.75))


func _server_keys(corp: Dictionary) -> Array:
	var remotes: Array = []
	for k in corp.get("servers", {}).keys():
		if k.begins_with("remote"):
			remotes.append(k)
	remotes.sort_custom(func(a, b): return int(a.substr(6)) < int(b.substr(6)))
	return ["archives", "rd", "hq"] + remotes


## Computes where every visible card belongs, then creates/moves/removes CardViews to match.
func _render() -> void:
	var v: Dictionary = views[viewing]
	var corp: Dictionary = v.corp
	var runner: Dictionary = v.runner
	var place := {}  # key -> [card, pos, rot_y]

	var keys := _server_keys(corp)
	var x0 := -float(keys.size() - 1) * COL_STEP / 2.0 - 0.6
	for i in keys.size():
		var x := x0 + i * COL_STEP
		var server: Dictionary = corp.servers.get(keys[i], {})
		var content = server.get("content")
		if content is Array:
			for j in content.size():
				place[content[j].get("cid", "%s-c%d" % [keys[i], j])] = [content[j], Vector3(x + j * 0.18, 0.01 + j * 0.03, -3.0 + j * 0.12), 0.0]
		var ices = server.get("ices")
		if ices is Array:
			for j in ices.size():
				place[ices[j].get("cid", "%s-i%d" % [keys[i], j])] = [ices[j], Vector3(x, 0.01, -1.95 + j * 0.72), PI / 2]
		match keys[i]:
			"hq":
				place["corp-id"] = [corp.identity, Vector3(x, 0.01, -3.0), 0.0]
			"rd":
				_pile(place, "corp-deck", corp.get("deck-count", 0), {}, Vector3(x, 0, -3.0))
			"archives":
				var discard: Array = _arr(corp.get("discard"))
				_pile(place, "corp-discard", discard.size(), discard.back() if discard.size() > 0 else {}, Vector3(x, 0, -3.0))

	_hand(place, "corp", corp, -4.1)
	_row(place, _arr(corp.get("scored")), Vector3(4.6, 0, -3.0), 0.25)

	var rig: Dictionary = runner.get("rig", {})
	place["runner-id"] = [runner.identity, Vector3(-4.6, 0.01, 2.2), 0.0]
	_row(place, _arr(rig.get("program")), Vector3(-3.4, 0, 1.25), 0.75)
	_row(place, _arr(rig.get("hardware")), Vector3(-3.4, 0, 2.25), 0.75)
	_row(place, _arr(rig.get("resource")), Vector3(-3.4, 0, 3.25), 0.75)
	_pile(place, "runner-deck", runner.get("deck-count", 0), {}, Vector3(3.6, 0, 1.25))
	var heap: Array = _arr(runner.get("discard"))
	_pile(place, "runner-heap", heap.size(), heap.back() if heap.size() > 0 else {}, Vector3(3.6, 0, 2.3))
	_row(place, _arr(runner.get("scored")), Vector3(4.6, 0, 3.3), 0.25)
	_hand(place, "runner", runner, 4.05)

	for key in cards.keys():
		if not place.has(key):
			cards[key].queue_free()
			cards.erase(key)
	for key in place:
		var p: Array = place[key]
		var view: CardView = cards.get(key)
		var fresh := view == null
		if fresh:
			view = CardView.new()
			view.clicked.connect(_on_card_clicked)
			view.hovered.connect(_on_card_hovered)
			add_child(view)
			cards[key] = view
			view.position = p[1] + Vector3(0, 2, 0)
		var c: Dictionary = p[0]
		var back := "corp" if str(c.get("side", "")).to_lower() == "corp" or key.begins_with("corp") else "runner"
		view.set_card(c, _texture(str(c.get("code", ""))) if c.has("title") else null, back)
		view.move_to(p[1], p[2], true)
	_render_ui()


func _hand(place: Dictionary, side: String, player: Dictionary, z: float) -> void:
	var hand: Array = _arr(player.get("hand"))
	if hand.is_empty():
		for i in int(player.get("hand-count", 0)):
			hand.append({"side": side.capitalize()})
	var n := hand.size()
	for i in n:
		var c: Dictionary = hand[i]
		var key: String = c.get("cid", "%s-hand-%d" % [side, i])
		var x := (i - (n - 1) / 2.0) * 0.72
		place[key] = [c, Vector3(x, 0.01 + i * 0.002, z), 0.0]


func _row(place: Dictionary, row: Array, origin: Vector3, step: float) -> void:
	for i in row.size():
		var c: Dictionary = row[i]
		place[c.get("cid", "row-%d-%d" % [origin.z, i])] = [c, origin + Vector3(i * step, 0.01 + i * 0.01, 0), 0.0]


## Decks and discards render as a single stacked card whose height tracks the count.
func _pile(place: Dictionary, key: String, count: int, top: Dictionary, pos: Vector3) -> void:
	if count <= 0:
		return
	var c := top.duplicate()
	if c.is_empty():
		c = {"side": "Corp" if key.begins_with("corp") else "Runner"}
	c["counter"] = {"cards": count}
	place[key] = [c, pos + Vector3(0, 0.01 + count * 0.004, 0), 0.0]


func _texture(code: String) -> Texture2D:
	if code == "" or not images:
		return null
	if textures.has(code):
		return textures[code]
	textures[code] = null
	var path := IMG_DIR.path_join(code + ".jpg")
	if FileAccess.file_exists(path):
		textures[code] = ImageTexture.create_from_image(Image.load_from_file(path))
		return textures[code]
	var req := HTTPRequest.new()
	req.download_file = path
	add_child(req)
	req.request_completed.connect(func(result: int, code_http: int, _h, _b):
		req.queue_free()
		if result == HTTPRequest.RESULT_SUCCESS and code_http == 200:
			textures[code] = ImageTexture.create_from_image(Image.load_from_file(path))
			if not views.is_empty():
				_render()
		else:
			DirAccess.remove_absolute(path))
	req.request(IMG_URL % code)
	return null


# --- Input ------------------------------------------------------------------

func _on_card_clicked(view: CardView, button: int) -> void:
	var c := view.card
	if not c.has("cid"):
		return
	var side := viewing
	var ref := {}
	for k in ["cid", "zone", "side", "type", "host"]:
		if c.has(k):
			ref[k] = c[k]
	var ps := _prompt(side)
	var mine := str(c.get("side", "")).to_lower() == side
	var zone: Array = _arr(c.get("zone"))
	if ps.get("prompt-type") == "select":
		act(side, "select", {"card": ref, "eid": ps.get("eid")})
	elif not mine:
		return
	elif button == MOUSE_BUTTON_RIGHT and side == "corp":
		act(side, "advance", {"card": ref})
	elif zone.size() > 0 and zone[0] == "hand":
		act(side, "play", {"card": ref})
	elif side == "corp" and c.get("installed") and not c.get("rezzed", false) and c.get("type") != "Agenda":
		act(side, "rez", {"card": ref})
	elif side == "corp" and c.get("type") == "Agenda" and c.get("installed"):
		act(side, "score", {"card": ref})
	elif _arr(c.get("abilities")).size() > 0:
		act(side, "ability", {"card": ref, "ability": 0})


func _on_card_hovered(view: CardView, entered: bool) -> void:
	if not entered:
		return
	var c := view.card
	preview.texture = textures.get(str(c.get("code", "")))
	var lines: Array[String] = [str(c.get("title", "(hidden)"))]
	if c.has("type"):
		lines.append("%s  %s" % [c.type, " - ".join(_arr(c.get("subtypes")))])
	for s in _arr(c.get("subroutines")):
		lines.append("↳ " + str(s.get("label", "")))
	for a in _arr(c.get("abilities")):
		lines.append("• " + str(a.get("label", "")))
	preview_text.text = "\n".join(lines)


# --- HUD --------------------------------------------------------------------

func _build_ui() -> void:
	var layer := CanvasLayer.new()
	add_child(layer)

	var top := PanelContainer.new()
	top.position = Vector2(10, 10)
	layer.add_child(top)
	var top_v := VBoxContainer.new()
	top.add_child(top_v)
	status_label = Label.new()
	status_label.text = "Starting rules engine..."
	top_v.add_child(status_label)
	perf_label = Label.new()
	perf_label.modulate = Color(0.6, 0.9, 0.7)
	top_v.add_child(perf_label)

	var bottom := PanelContainer.new()
	bottom.anchor_top = 1.0
	bottom.anchor_bottom = 1.0
	bottom.anchor_right = 0.66
	bottom.offset_top = -64
	bottom.offset_left = 10
	bottom.offset_bottom = -10
	layer.add_child(bottom)
	actions_box = HFlowContainer.new()
	bottom.add_child(actions_box)

	var right := PanelContainer.new()
	right.anchor_left = 1.0
	right.anchor_right = 1.0
	right.anchor_bottom = 1.0
	right.offset_left = -330
	right.offset_right = -10
	right.offset_top = 10
	right.offset_bottom = -10
	layer.add_child(right)
	var right_v := VBoxContainer.new()
	right.add_child(right_v)
	prompt_box = VBoxContainer.new()
	right_v.add_child(prompt_box)
	right_v.add_child(HSeparator.new())
	preview = TextureRect.new()
	preview.custom_minimum_size = Vector2(0, 300)
	preview.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	preview.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	right_v.add_child(preview)
	preview_text = Label.new()
	preview_text.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	preview_text.add_theme_font_size_override("font_size", 13)
	right_v.add_child(preview_text)
	right_v.add_child(HSeparator.new())
	log_label = RichTextLabel.new()
	log_label.size_flags_vertical = Control.SIZE_EXPAND_FILL
	log_label.scroll_following = true
	log_label.add_theme_font_size_override("normal_font_size", 13)
	right_v.add_child(log_label)


func _button(parent: Control, text: String, f: Callable) -> void:
	var b := Button.new()
	b.text = text
	b.pressed.connect(f)
	parent.add_child(b)


func _render_ui() -> void:
	var v: Dictionary = views[viewing]
	var corp: Dictionary = v.corp
	var runner: Dictionary = v.runner
	status_label.text = "Turn %d — %s's turn — viewing as %s\nCorp:   %d¢  %d clicks  %d AP  hand %d  R&D %d\nRunner: %d¢  %d clicks  %d AP  hand %d  stack %d  tags %d" % [
		int(v.get("turn", 0)), str(v.get("active-player", "")).capitalize(), viewing.to_upper(),
		corp.get("credit", 0), corp.get("click", 0), corp.get("agenda-point", 0), corp.get("hand-count", 0), corp.get("deck-count", 0),
		runner.get("credit", 0), runner.get("click", 0), runner.get("agenda-point", 0), runner.get("hand-count", 0), runner.get("deck-count", 0),
		_num(runner.get("tag")),
	]
	perf_label.text = "sidecar boot %d ms · last action: engine %.1f ms, round trip %.1f ms" % [perf.boot, perf.engine, perf.rtt]

	for child in prompt_box.get_children():
		child.queue_free()
	var ps := _prompt(viewing)
	var msg := Label.new()
	msg.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	msg.text = str(ps.get("msg", "No prompt.")) if not ps.is_empty() else "No prompt."
	prompt_box.add_child(msg)
	var choices = ps.get("choices")
	if choices is Array:
		for c in choices:
			if c is Dictionary and c.has("uuid"):
				var value := str(c.get("value"))
				_button(prompt_box, value, choose.bind(viewing, value))
	elif choices is Dictionary and choices.has("number"):
		for n in int(choices["number"]) + 1:
			_button(prompt_box, str(n), act.bind(viewing, "choice", {"choice": n, "eid": ps.get("eid")}))
	if ps.get("prompt-type") == "select":
		var hint := Label.new()
		hint.text = "(click cards on the table to select)"
		prompt_box.add_child(hint)

	for child in actions_box.get_children():
		child.queue_free()
	var me: Dictionary = v[viewing]
	_button(actions_box, "View: %s ⇄" % viewing.capitalize(), func():
		manual_view = true
		viewing = _other(viewing)
		_render())
	if manual_view:
		_button(actions_box, "Auto view", func():
			manual_view = false
			_auto_view()
			_render())
	var my_turn: bool = v.get("active-player") == viewing
	if my_turn and me.get("click", 0) == 0 and not v.get("end-turn", false):
		_button(actions_box, "End Turn", act.bind(viewing, "end-turn"))
	if my_turn and v.get("end-turn", false) or v.get("turn", 0) == 0:
		_button(actions_box, "Start Turn", act.bind(viewing, "start-turn"))
	if my_turn and me.get("click", 0) > 0 and not v.get("run"):
		_button(actions_box, "Gain 1¢", act.bind(viewing, "credit"))
		_button(actions_box, "Draw", act.bind(viewing, "draw"))
		if viewing == "corp":
			_button(actions_box, "Purge", act.bind(viewing, "purge"))
		else:
			for server in _arr(me.get("runnable-list")):
				_button(actions_box, "Run %s" % server, act.bind(viewing, "run", {"server": server}))
			if _num(me.get("tag")) > 0:
				_button(actions_box, "Remove Tag", act.bind(viewing, "remove-tag"))
	if v.get("run"):
		_button(actions_box, "Continue", func():
			act("corp", "continue")
			act("runner", "continue"))
		if viewing == "runner":
			_button(actions_box, "Jack Out", act.bind("runner", "jack-out"))

	var lines: Array[String] = []
	for entry in _arr(v.get("log")).slice(-14):
		lines.append(_log_text(entry))
	log_label.text = "\n".join(lines)


func _arr(x) -> Array:
	return x if x is Array else []


func _num(x) -> int:
	if x is Dictionary:
		return int(x.get("base", 0)) + int(x.get("additional", 0))
	return int(x) if x != null else 0


func _log_text(entry) -> String:
	var t = entry.get("text") if entry is Dictionary else entry
	if t is Dictionary:
		if t.get("raw-text") != null:
			t = t["raw-text"]
		else:
			t = "%s: %s" % [t.get("msg/username", ""), t.get("msg/type", "")]
	return str(t).replace("[Credits]", "¢").replace("[Click]", "[click]").replace("[hr]", "———")


func _append_error(e: String) -> void:
	log_label.append_text("\n[color=red]Engine error: %s[/color]" % e)
	push_error(e)


# --- Autoplay (scripted demo used for screenshots) ----------------------------

func _first_in_hand(side: String, types: Array) -> Dictionary:
	for c in _arr(views[side][side].get("hand")):
		if c.get("type") in types and c.get("cost", 0) <= views[side][side].get("credit", 0):
			return c
	return {}


func _play_first(side: String, types: Array) -> void:
	var c := _first_in_hand(side, types)
	if c.is_empty():
		act(side, "credit")
	else:
		act(side, "play", {"card": {"cid": c.cid, "zone": c.zone, "side": c.side, "type": c.type}})


## Exercises real 3D picking: injects a mouse click at the card's projected screen position.
func _click_hand(side: String, types: Array) -> void:
	var view: CardView = cards.get(_first_in_hand(side, types).get("cid", ""))
	if view == null:
		act(side, "credit")
		return
	var pt := get_viewport().get_camera_3d().unproject_position(view.global_position)
	var motion := InputEventMouseMotion.new()
	motion.position = pt
	Input.parse_input_event(motion)
	await get_tree().physics_frame
	await get_tree().physics_frame
	for pressed in [true, false]:
		var ev := InputEventMouseButton.new()
		ev.button_index = MOUSE_BUTTON_LEFT
		ev.pressed = pressed
		ev.position = pt
		Input.parse_input_event(ev)
		await get_tree().physics_frame
	print("autoplay: clicked %s at %s" % [view.card.get("title"), pt])


func _choose_any(side: String, preferred: String) -> void:
	var ps := _prompt(side)
	var values: Array = []
	for c in _arr(ps.get("choices")):
		if c is Dictionary and c.has("uuid"):
			values.append(str(c.value))
	if values.is_empty():
		act(side, "credit")
	else:
		choose(side, preferred if preferred in values else values[0])


func _queue_autoplay() -> void:
	autoplay = [
		func(): choose("corp", "Keep"),
		func(): choose("runner", "Keep"),
		func(): act("corp", "start-turn"),
		func(): _play_first("corp", ["ICE"]),
		func(): _choose_any("corp", "HQ"),
		func(): _play_first("corp", ["Asset", "Agenda"]),
		func(): _choose_any("corp", "New remote"),
		func(): _play_first("corp", ["ICE"]),
		func(): _choose_any("corp", "R&D"),
		func(): act("corp", "end-turn"),
		func(): act("runner", "start-turn"),
		func(): _play_first("runner", ["Program", "Hardware", "Resource"]),
		func(): _click_hand("runner", ["Program", "Hardware", "Resource"]),
		func(): act("runner", "run", {"server": "Archives"}),
		func(): act("corp", "continue"),
	]


func _autoplay_step() -> void:
	if autoplay.is_empty():
		return
	var step: Callable = autoplay.pop_front()
	step.call()
	if autoplay.is_empty() and args.has("screenshot"):
		await get_tree().create_timer(1.5).timeout
		get_viewport().get_texture().get_image().save_png(str(args["screenshot"]))
		print("saved screenshot to %s" % args["screenshot"])
		get_tree().quit()
