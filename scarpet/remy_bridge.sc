__config() -> {
    'scope' -> 'global',
    'stay_loaded' -> true
};

global_remy_name = 'remy';
global_last_command_id = null;
global_next_poll_tick = 0;
global_move_expires_ms = 0;
global_is_moving = false;

__run_player(command) -> run('player ' + global_remy_name + ' ' + command);

__command_result(result) -> {
    'success' -> result:0,
    'messages' -> result:1,
    'error' -> if(result:2, str(result:2), null)
};

__stop_remy() -> (
    global_is_moving = false;
    global_move_expires_ms = 0;
    __command_result(__run_player('stop'))
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
        'remy' -> global_remy_name,
        'online' -> bool(p),
        'moving' -> global_is_moving,
        'moveExpiresMs' -> global_move_expires_ms
    };
    if(!p, return(base));
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
    base:'surroundings' = __surroundings(center);
    base
);

__ack(id, action, status, detail) -> (
    write_file('ack', 'json', {
        'id' -> id,
        'action' -> action,
        'status' -> status,
        'detail' -> detail,
        'state' -> __state()
    })
);

__spawn() -> (
    p = player(global_remy_name);
    if(p,
        return({'alreadyOnline' -> true})
    );
    __command_result(run('execute as @a[name!=' + global_remy_name + ',limit=1] at @s run player ' + global_remy_name + ' spawn in survival'))
);

__look(cmd) -> (
    direction = cmd:'direction';
    allowed = ['north', 'south', 'east', 'west', 'up', 'down'];
    if(!first(allowed, _ == direction),
        return({'error' -> 'look direction must be one of north/south/east/west/up/down'})
    );
    __command_result(__run_player('look ' + direction))
);

__move(cmd, now_ms) -> (
    direction = cmd:'direction';
    allowed = ['forward', 'backward', 'left', 'right'];
    if(!first(allowed, _ == direction),
        return({'error' -> 'move direction must be one of forward/backward/left/right'})
    );
    duration_ms = min(max(number(cmd:'durationMs'), 1), 1000);
    result = __command_result(__run_player('move ' + direction));
    global_is_moving = true;
    global_move_expires_ms = min(now_ms + duration_ms, number(cmd:'expiresMs'));
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
    expires_ms = number(cmd:'expiresMs');
    if(!expires_ms || now_ms > expires_ms,
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
    __ack(id, action, 'ok', detail)
);

__poll_remy_bridge() -> (
    now_ms = unix_time();
    if(global_is_moving && global_move_expires_ms && now_ms > global_move_expires_ms,
        __stop_remy()
    );
    cmd = read_file('command', 'json');
    if(cmd,
        __execute(cmd, now_ms)
    );
    write_file('state', 'json', __state())
);

__on_tick() -> (
    now_tick = tick_time();
    if(now_tick < global_next_poll_tick, return());
    global_next_poll_tick = now_tick + 5;
    __poll_remy_bridge()
);
