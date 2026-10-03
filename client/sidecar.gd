## Spawns the JVM rules-engine sidecar and talks newline-delimited JSON over its stdio.
class_name Sidecar
extends Node

signal message(msg: Dictionary)
signal died

var _pid := -1
var _stdio: FileAccess
var _thread: Thread
var _mutex := Mutex.new()
var _inbox: Array[String] = []
var _next_id := 0
var _sent_at := {}
var launch_ms := 0


func start() -> Error:
	var cmd := _command()
	if cmd.is_empty():
		push_error("Sidecar: no bundle in dist/ and no JDK + sidecar/target/sidecar.jar found")
		return ERR_FILE_NOT_FOUND
	print("Sidecar: %s" % " ".join(cmd))
	launch_ms = Time.get_ticks_msec()
	var proc := OS.execute_with_pipe(cmd[0], cmd.slice(1))
	if proc.is_empty():
		return ERR_CANT_FORK
	_pid = proc["pid"]
	_stdio = proc["stdio"]
	_thread = Thread.new()
	_thread.start(_read_loop)
	return OK


func send(req: Dictionary) -> int:
	_next_id += 1
	req["id"] = _next_id
	_sent_at[_next_id] = Time.get_ticks_usec()
	_stdio.store_line(JSON.stringify(req))
	_stdio.flush()
	return _next_id


## Round-trip time in ms for a response, measured on the Godot side.
func rtt_ms(id: int) -> float:
	if not _sent_at.has(id):
		return -1.0
	return (Time.get_ticks_usec() - _sent_at[id]) / 1000.0


func _read_loop() -> void:
	while true:
		var line := _stdio.get_line()
		if _stdio.get_error() != OK and line == "":
			break
		if line == "":
			continue
		_mutex.lock()
		_inbox.append(line)
		_mutex.unlock()
	call_deferred("emit_signal", "died")


func _process(_delta: float) -> void:
	_mutex.lock()
	var lines := _inbox.duplicate()
	_inbox.clear()
	_mutex.unlock()
	for line in lines:
		if not line.begins_with("{"):
			print("Sidecar stdout: %s" % line)
			continue
		var parsed = JSON.parse_string(line)
		if parsed is Dictionary:
			message.emit(parsed)
		else:
			push_error("Sidecar: bad line %s" % line.substr(0, 200))


## Asks the sidecar to exit cleanly (so the JVM can write its CDS archive), killing it if it hangs.
func stop() -> void:
	if _pid <= 0:
		return
	if OS.is_process_running(_pid):
		send({"op": "quit"})
		var deadline := Time.get_ticks_msec() + 5000
		while OS.is_process_running(_pid) and Time.get_ticks_msec() < deadline:
			OS.delay_msec(20)
		if OS.is_process_running(_pid):
			OS.kill(_pid)
	_pid = -1


func _exit_tree() -> void:
	stop()
	if _thread and _thread.is_started():
		_thread.wait_to_finish()


## Prefers a bundle (jlink runtime + jar + CDS archive) next to the executable or in dist/,
## falling back to a development JDK and the freshly built uberjar.
func _command() -> PackedStringArray:
	# -Xlog:disable keeps JVM warnings off stdout. The CDS archive is created on first launch and
	# reused afterwards, which roughly halves boot time; it lives in user:// because it is tied
	# to the jar's absolute path and JVM build.
	var flags := ["-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xmx256m", "-Xlog:disable", "-Xlog:all=error:stderr"]
	for dir in [OS.get_executable_path().get_base_dir(), ProjectSettings.globalize_path("res://").path_join("../dist")]:
		dir = dir.simplify_path()
		var java: String = dir.path_join("runtime/bin/java")
		if FileAccess.file_exists(java) and FileAccess.file_exists(dir.path_join("sidecar.jar")):
			flags.append("-XX:+AutoCreateSharedArchive")
			flags.append("-XX:SharedArchiveFile=" + ProjectSettings.globalize_path("user://sidecar.jsa"))
			return PackedStringArray([java] + flags + ["-jar", dir.path_join("sidecar.jar")])
	var jar := ProjectSettings.globalize_path("res://").path_join("../sidecar/target/sidecar.jar").simplify_path()
	var homes: Array[String] = []
	for env in ["MONOLITH_JAVA_HOME", "JAVA_HOME"]:
		if OS.has_environment(env):
			homes.append(OS.get_environment(env))
	homes.append("/opt/homebrew/opt/openjdk@21")
	for home in homes:
		if FileAccess.file_exists(home.path_join("bin/java")) and FileAccess.file_exists(jar):
			return PackedStringArray([home.path_join("bin/java")] + flags + ["-jar", jar])
	return PackedStringArray()
