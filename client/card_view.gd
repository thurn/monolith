## A single card lying flat on the table: a thin box, an art quad on top, and a caption.
class_name CardView
extends Node3D

signal clicked(view: CardView, button: int)
signal hovered(view: CardView, entered: bool)

const W := 0.63
const H := 0.88
const BACK_CORP := Color(0.10, 0.16, 0.32)
const BACK_RUNNER := Color(0.30, 0.10, 0.12)
const TYPE_COLORS := {
	"ICE": Color(0.55, 0.30, 0.10),
	"Agenda": Color(0.45, 0.10, 0.45),
	"Asset": Color(0.15, 0.45, 0.25),
	"Upgrade": Color(0.20, 0.35, 0.55),
	"Operation": Color(0.55, 0.50, 0.15),
	"Event": Color(0.55, 0.50, 0.15),
	"Program": Color(0.15, 0.40, 0.55),
	"Hardware": Color(0.35, 0.35, 0.40),
	"Resource": Color(0.20, 0.50, 0.30),
	"Identity": Color(0.20, 0.20, 0.25),
}

var card: Dictionary = {}
var face_up := false
var _body_mat := StandardMaterial3D.new()
var _face_mat := StandardMaterial3D.new()
var _face: MeshInstance3D
var _title: Label3D
var _badge: Label3D
var _tween: Tween


func _init() -> void:
	var body := StaticBody3D.new()
	var shape := CollisionShape3D.new()
	var box_shape := BoxShape3D.new()
	box_shape.size = Vector3(W, 0.04, H)
	shape.shape = box_shape
	body.add_child(shape)
	body.input_event.connect(_on_input_event)
	body.mouse_entered.connect(func(): hovered.emit(self, true))
	body.mouse_exited.connect(func(): hovered.emit(self, false))
	add_child(body)

	var mesh := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = Vector3(W, 0.02, H)
	mesh.mesh = box
	mesh.material_override = _body_mat
	add_child(mesh)

	_face = MeshInstance3D.new()
	var quad := QuadMesh.new()
	quad.size = Vector2(W * 0.94, H * 0.94)
	_face.mesh = quad
	_face.rotation_degrees.x = -90
	_face.position.y = 0.011
	_face.material_override = _face_mat
	_face.visible = false
	add_child(_face)

	_title = _make_label(0.0022, 34)
	_title.position = Vector3(0, 0.013, 0)
	_title.width = 260
	_title.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	add_child(_title)

	_badge = _make_label(0.003, 40)
	_badge.position = Vector3(0, 0.014, H * 0.5 + 0.07)
	_badge.modulate = Color(1, 0.9, 0.4)
	add_child(_badge)


func _make_label(pixel_size: float, font_size: int) -> Label3D:
	var l := Label3D.new()
	l.pixel_size = pixel_size
	l.font_size = font_size
	l.outline_size = 8
	l.rotation_degrees.x = -90
	l.double_sided = false
	return l


func set_card(c: Dictionary, texture: Texture2D, back_side: String) -> void:
	card = c
	face_up = c.has("title")
	var rezzed: bool = not c.get("installed") or c.get("rezzed", false) or c.get("side") == "Runner" \
		or c.get("type") in ["Agenda", "Identity"]
	if face_up:
		_body_mat.albedo_color = TYPE_COLORS.get(c.get("type", ""), Color(0.3, 0.3, 0.3))
		_title.text = c.get("title", "")
		_face.visible = texture != null
		_face_mat.albedo_texture = texture
		_face_mat.albedo_color = Color.WHITE if rezzed else Color(0.45, 0.45, 0.55)
		_title.visible = texture == null or not rezzed
		if not rezzed:
			_title.text = "(unrezzed)\n" + _title.text
	else:
		_body_mat.albedo_color = BACK_CORP if back_side == "corp" else BACK_RUNNER
		_face.visible = false
		_title.visible = true
		_title.text = "CORP" if back_side == "corp" else "RUNNER"
	_badge.text = _badge_text(c)


func _badge_text(c: Dictionary) -> String:
	var parts: Array[String] = []
	if c.get("advance-counter", 0) > 0:
		parts.append("adv %d" % c["advance-counter"])
	var counters = c.get("counter")
	if counters is Dictionary:
		for k in counters:
			if counters[k] > 0:
				parts.append("%s %d" % [k, counters[k]])
	if c.get("type") == "ICE" and c.get("rezzed", false):
		parts.append("str %d" % c.get("current-strength", c.get("strength", 0)))
	if c.get("selected", false):
		parts.append("SELECTED")
	return "  ".join(parts)


func move_to(pos: Vector3, rot_y: float, animate: bool) -> void:
	if _tween:
		_tween.kill()
	if not animate:
		position = pos
		rotation.y = rot_y
		return
	_tween = create_tween().set_parallel().set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	# Lift the card while it travels so moves read as "picked up and placed".
	var lift := Vector3(0, 0.6, 0) if position.distance_to(pos) > 0.2 else Vector3.ZERO
	_tween.tween_property(self, "position", (position + pos) / 2 + lift, 0.18)
	_tween.chain().tween_property(self, "position", pos, 0.22)
	_tween.tween_property(self, "rotation:y", rot_y, 0.4)


func _on_input_event(_cam: Node, event: InputEvent, _pos: Vector3, _normal: Vector3, _idx: int) -> void:
	if event is InputEventMouseButton and event.pressed:
		clicked.emit(self, event.button_index)
