__config() -> {
    'scope' -> 'global',
    'stay_loaded' -> true
};

global_remy_name = 'remy';
global_bridge_version = '0.1.5';
global_max_command_ttl_ms = 10000;
global_clock_skew_ms = 10000;
global_last_command_id = null;
global_next_poll_tick = 0;
global_next_heartbeat_tick = 0;
global_heartbeat_slots = 12;
global_move_expires_ms = 0;
global_is_moving = false;
global_pending_ack = null;
global_writer_fault = null;

__run_player(command) -> run('player ' + global_remy_name + ' ' + command);

__command_result(result) -> {
    'success' -> result:0,
    'messages' -> result:1,
    'error' -> if(result:2, str(result:2), null)
};

__remy_player_type(p) -> if(p, query(p, 'player_type'), null);

__remy_is_fake(p) -> p && __remy_player_type(p) == 'fake';

__write_json_safe(file_name, payload) -> (
    ok = try(
        (write_file(file_name, 'json', payload); true),
        'io_exception',
        (
            global_writer_fault = {
                'file' -> file_name,
                'tick' -> tick_time(),
                'unixMs' -> unix_time()
            };
            false
        )
    );
    ok
);

__stop_remy() -> (
    p = player(global_remy_name);
    if(!p,
        global_is_moving = false;
        global_move_expires_ms = 0;
        return({'success' -> 1, 'messages' -> [], 'alreadyOffline' -> true})
    );
    player_type = __remy_player_type(p);
    if(player_type != 'fake',
        return({'success' -> 0, 'error' -> 'refusing to control non-fake player named remy', 'playerType' -> player_type})
    );
    result = __command_result(__run_player('stop'));
    if(result:'success',
        global_is_moving = false;
        global_move_expires_ms = 0
    );
    result
);

__heartbeat(reason) -> (
    now_ms = unix_time();
    slot = floor(tick_time() / 200) % global_heartbeat_slots;
    __write_json_safe('heartbeat_' + slot, {
        'bridgeVersion' -> global_bridge_version,
        'gameTarget' -> system_info('game_target'),
        'gameVersion' -> system_info('game_version'),
        'reason' -> reason,
        'slot' -> slot,
        'tick' -> tick_time(),
        'unixMs' -> now_ms,
        'worldFolder' -> system_info('world_folder'),
        'worldPath' -> system_info('world_path')
    })
);

__maybe_heartbeat(reason) -> (
    now_tick = tick_time();
    if(now_tick >= global_next_heartbeat_tick,
        global_next_heartbeat_tick = now_tick + 200;
        __heartbeat(reason)
    )
);

__enforce_move_expiry(now_ms) -> (
    if(global_is_moving && global_move_expires_ms && now_ms >= global_move_expires_ms,
        __stop_remy()
    )
);

__inventory_state(p) -> (
    inv = [];
    size = inventory_size(p);
    if(size,
        loop(size,
            item = inventory_get(p, _);
            if(item,
                inv += [{
                    'slot' -> _,
                    'item' -> item:0,
                    'count' -> item:1,
                    'nbt' -> str(item:2)
                }]
            )
        )
    );
    inv
);

__block_entry(where) -> (
    b = block(where);
    {
        'pos' -> where,
        'name' -> str(b),
        'state' -> block_state(b)
    }
);

__surroundings(center) -> (
    [cx, cy, cz] = center;
    blocks = [];
    loop(3, dx = _ - 1;
        loop(3, dy = _ - 1;
            loop(3, dz = _ - 1;
                here = [cx + dx, cy + dy, cz + dz];
                blocks += [__block_entry(here)]
            )
        )
    );
    blocks
);

__state() -> (
    p = player(global_remy_name);
    base = {
        'tick' -> tick_time(),
        'unixMs' -> unix_time(),
        'worldFolder' -> system_info('world_folder'),
        'gameVersion' -> system_info('game_version'),
        'gameTarget' -> system_info('game_target'),
        'bridgeVersion' -> global_bridge_version,
        'worldPath' -> system_info('world_path'),
        'remy' -> global_remy_name,
        'online' -> bool(p),
        'moving' -> global_is_moving,
        'moveExpiresMs' -> global_move_expires_ms,
        'writerFault' -> global_writer_fault
    };
    if(!p, return(base));
    player_type = __remy_player_type(p);
    base:'playerType' = player_type;
    base:'controllable' = player_type == 'fake';
    if(player_type != 'fake', return(base));
    pos = query(p, 'pos');
    center = map(pos, floor(_));
    base:'dimension' = query(p, 'dimension');
    base:'pos' = pos;
    base:'centerBlock' = center;
    base:'yaw' = query(p, 'yaw');
    base:'pitch' = query(p, 'pitch');
    base:'look' = query(p, 'look');
    base:'motion' = query(p, 'motion');
    base:'gamemode' = query(p, 'gamemode');
    base:'inventory' = __inventory_state(p);
    base:'surroundingsRadius' = 1;
    base:'surroundings' = in_dimension(p, __surroundings(center));
    base
);

__ack(id, action, status, detail) -> (
    global_pending_ack = {
        'id' -> id,
        'action' -> action,
        'status' -> status,
        'detail' -> detail,
        'state' -> __state()
    };
    __flush_pending_ack()
);

__flush_pending_ack() -> (
    if(!global_pending_ack, return(true));
    id = str(global_pending_ack:'id');
    if(!id, return(false));
    ok = __write_json_safe('ack_' + id, global_pending_ack);
    if(ok,
        global_pending_ack = null
    );
    ok
);

__spawn() -> (
    p = player(global_remy_name);
    if(p,
        if(!__remy_is_fake(p),
            return({'success' -> 0, 'error' -> 'refusing to use existing non-fake player named remy', 'playerType' -> __remy_player_type(p)})
        );
        return({'alreadyOnline' -> true})
    );
    __command_result(run('execute as @a[name=!' + global_remy_name + ',limit=1] at @s run player ' + global_remy_name + ' spawn'))
);

__look(cmd) -> (
    direction = cmd:'direction';
    allowed = ['north', 'south', 'east', 'west', 'up', 'down'];
    if(!first(allowed, _ == direction),
        return({'error' -> 'look direction must be one of north/south/east/west/up/down'})
    );
    p = player(global_remy_name);
    if(!__remy_is_fake(p),
        return({'success' -> 0, 'error' -> 'remy must be an online fake player before look', 'playerType' -> __remy_player_type(p)})
    );
    __command_result(__run_player('look ' + direction))
);

__move(cmd, now_ms) -> (
    direction = cmd:'direction';
    allowed = ['forward', 'backward', 'left', 'right'];
    if(!first(allowed, _ == direction),
        return({'error' -> 'move direction must be one of forward/backward/left/right'})
    );
    p = player(global_remy_name);
    if(!__remy_is_fake(p),
        return({'success' -> 0, 'error' -> 'remy must be an online fake player before movement', 'playerType' -> __remy_player_type(p)})
    );
    duration_ms = min(max(number(cmd:'durationMs'), 1), 1000);
    pre_stop = __stop_remy();
    if(!pre_stop:'success',
        return({'success' -> 0, 'error' -> 'pre-move stop failed', 'stopResult' -> pre_stop})
    );
    result = __command_result(__run_player('move ' + direction));
    if(result:'success',
        global_is_moving = true;
        global_move_expires_ms = min(now_ms + duration_ms, number(cmd:'expiresMs')),
        global_is_moving = false;
        global_move_expires_ms = 0
    );
    result:'durationMs' = duration_ms;
    result:'autoStopAtMs' = global_move_expires_ms;
    result
);

__execute(cmd, now_ms) -> (
    id = str(cmd:'id');
    action = cmd:'action';
    if(!id || !action,
        return(__ack(id, action, 'rejected', {'error' -> 'command requires id and action'}))
    );
    if(id == global_last_command_id, return());
    global_last_command_id = id;
    created_ms = number(cmd:'createdMs');
    expires_ms = number(cmd:'expiresMs');
    if(!created_ms || !expires_ms || expires_ms <= created_ms || expires_ms - created_ms > global_max_command_ttl_ms || created_ms > now_ms + global_clock_skew_ms,
        delete_file('command', 'json');
        return(__ack(id, action, 'rejected', {'error' -> 'command timing is invalid', 'nowMs' -> now_ms, 'createdMs' -> created_ms, 'expiresMs' -> expires_ms}))
    );
    if(now_ms > expires_ms,
        delete_file('command', 'json');
        return(__ack(id, action, 'expired', {'nowMs' -> now_ms, 'expiresMs' -> expires_ms}))
    );
    allowed = ['spawn', 'status', 'look', 'move', 'stop'];
    if(!first(allowed, _ == action),
        delete_file('command', 'json');
        return(__ack(id, action, 'rejected', {'error' -> 'action is not allowlisted'}))
    );

    detail = if(
        action == 'spawn', __spawn(),
        action == 'status', {'ok' -> true},
        action == 'look', __look(cmd),
        action == 'move', __move(cmd, now_ms),
        action == 'stop', __stop_remy()
    );
    delete_file('command', 'json');
    status = if(detail:'success' == 0 || detail:'error', 'failed', 'ok');
    __ack(id, action, status, detail)
);

__poll_remy_bridge() -> (
    now_ms = unix_time();
    __enforce_move_expiry(now_ms);
    __flush_pending_ack();
    cmd = read_file('command', 'json');
    if(cmd,
        __execute(cmd, now_ms)
    );
    __maybe_heartbeat('tick')
);

__on_tick() -> (
    now_tick = tick_time();
    __enforce_move_expiry(unix_time());
    if(now_tick < global_next_poll_tick, return());
    global_next_poll_tick = now_tick + 5;
    __poll_remy_bridge()
);

__on_start() -> (
    __stop_remy();
    __heartbeat('start')
);

__on_close() -> (
    __stop_remy();
    __heartbeat('close')
);
