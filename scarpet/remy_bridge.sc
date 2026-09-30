__config() -> {
    'scope' -> 'global',
    'stay_loaded' -> true,
    'command_permission' -> _(p) -> __is_owner(p),
    'commands' -> {
        '' -> _() -> __command(),
        'follow' -> _() -> follow(),
        'stop' -> _() -> stop(),
        'status' -> _() -> status()
    }
};

global_remy_name = 'remy';
global_bridge_version = '0.1.9';
global_max_command_ttl_ms = 10000;
global_clock_skew_ms = 10000;
global_last_command_id = null;
global_next_poll_tick = 0;
global_next_heartbeat_tick = 0;
global_heartbeat_slots = 12;
global_follow_min_distance = 3.0;
global_follow_resume_distance = 4.0;
global_follow_max_distance = 24.0;
global_follow_max_vertical = 2.0;
global_follow_search_radius = 4;
global_follow_max_nodes = 80;
global_follow_max_drop = 2;
global_follow_stuck_limit = 16;
global_move_expires_ms = 0;
global_is_moving = false;
global_follow_active = false;
global_follow_owner_name = null;
global_follow_last_status = 'idle';
global_follow_last_route = null;
global_follow_last_pos = null;
global_follow_stuck_ticks = 0;
global_active_goal = 'idle';
global_goal_generation = 0;
global_follow_generation = 0;
global_pending_ack = null;
global_writer_fault = null;
global_runtime_self_test = null;

__run_player(command) -> run('player ' + global_remy_name + ' ' + command);

__command_result(result) -> {
    'success' -> result:0,
    'messages' -> result:1,
    'error' -> if(result:2, str(result:2), null)
};

__remy_player_type(p) -> if(p, query(p, 'player_type'), null);

__remy_is_fake(p) -> p && __remy_player_type(p) == 'fake';

__owner_name() -> (
    cfg = read_file('owner', 'json');
    if(cfg && cfg:'ownerName', str(cfg:'ownerName'), null)
);

__is_owner(p) -> (
    owner_name = __owner_name();
    p && owner_name && query(p, 'command_name') == owner_name
);

__owner_check(p) -> (
    owner_name = __owner_name();
    if(!owner_name,
        return({'success' -> 0, 'error' -> 'owner is not configured'})
    );
    if(!p,
        return({'success' -> 0, 'error' -> 'command requires a player'})
    );
    if(!__is_owner(p),
        caller = query(p, 'command_name');
        return({'success' -> 0, 'error' -> 'only the configured owner can command remy', 'caller' -> caller})
    );
    {'success' -> 1, 'ownerName' -> owner_name}
);

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

__queue_shape_self_test() -> (
    queue = [[[1, 2, 3], null, 0]];
    node = queue:0;
    pos = node:0;
    node_first = node:1;
    node_depth = node:2;
    {
        'ok' -> pos:0 == 1 && pos:1 == 2 && pos:2 == 3 && node_first == null && node_depth == 0,
        'pos' -> pos,
        'firstWasNull' -> node_first == null,
        'depth' -> node_depth
    }
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
        'runtimeSelfTest' -> global_runtime_self_test,
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

__block_name_at(where) -> str(block(where));

__is_name_in(value, names) -> bool(first(names, _ == value));

__is_passable_name(name) -> __is_name_in(name, [
    'air',
    'minecraft:air',
    'cave_air',
    'minecraft:cave_air',
    'void_air',
    'minecraft:void_air',
    'grass',
    'minecraft:grass',
    'tall_grass',
    'minecraft:tall_grass',
    'fern',
    'minecraft:fern',
    'large_fern',
    'minecraft:large_fern',
    'snow',
    'minecraft:snow'
]);

__is_dangerous_name(name) -> __is_name_in(name, [
    'lava',
    'minecraft:lava',
    'fire',
    'minecraft:fire',
    'soul_fire',
    'minecraft:soul_fire',
    'magma_block',
    'minecraft:magma_block',
    'cactus',
    'minecraft:cactus',
    'campfire',
    'minecraft:campfire',
    'soul_campfire',
    'minecraft:soul_campfire',
    'sweet_berry_bush',
    'minecraft:sweet_berry_bush',
    'powder_snow',
    'minecraft:powder_snow',
    'water',
    'minecraft:water'
]);

__is_support_name(name) -> name && !__is_passable_name(name) && !__is_dangerous_name(name);

__flat_distance(a, b) -> (
    [ax, ay, az] = a;
    [bx, by, bz] = b;
    dx = bx - ax;
    dz = bz - az;
    sqrt(dx * dx + dz * dz)
);

__distance3(a, b) -> (
    [ax, ay, az] = a;
    [bx, by, bz] = b;
    dx = bx - ax;
    dy = by - ay;
    dz = bz - az;
    sqrt(dx * dx + dy * dy + dz * dz)
);

__pos_key(pos) -> (
    [x, y, z] = pos;
    x + ',' + y + ',' + z
);

__candidate_at(base_y, x, z) -> (
    levels = [base_y, base_y + 1, base_y - 1, base_y - 2];
    result = null;
    loop(length(levels),
        y = levels:_;
        if(!result,
            foot = __block_name_at([x, y, z]);
            head = __block_name_at([x, y + 1, z]);
            below = __block_name_at([x, y - 1, z]);
            if(
                !__is_dangerous_name(foot) && !__is_dangerous_name(head) && !__is_dangerous_name(below)
                && __is_passable_name(foot) && __is_passable_name(head) && __is_support_name(below),
                result = {
                    'ok' -> true,
                    'pos' -> [x, y, z],
                    'foot' -> foot,
                    'head' -> head,
                    'below' -> below
                }
            )
        )
    );
    if(result, result, {'ok' -> false, 'reason' -> 'unsupported'})
);

__find_follow_step(remy_pos, owner_pos) -> (
    [rx, ry, rz] = map(remy_pos, floor(_));
    [ox, oy, oz] = map(owner_pos, floor(_));
    start = [rx, ry, rz];
    owner = [ox, oy, oz];
    start_distance = __flat_distance(start, owner);
    if(start_distance <= global_follow_min_distance,
        return({'ok' -> true, 'reason' -> 'near_owner', 'next' -> null, 'distance' -> start_distance, 'visited' -> 1})
    );

    dirs = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]];
    frontier = [[start, null, 0]];
    visited = [__pos_key(start)];
    best = null;
    best_score = 1000000;
    cursor = 0;
    while(cursor < length(frontier) && cursor < global_follow_max_nodes,
        node = frontier:cursor;
        cursor += 1;
        pos = node:0;
        node_first = node:1;
        node_depth = node:2;
        [cx, cy, cz] = pos;
        loop(length(dirs),
            d = dirs:_;
            nx = cx + d:0;
            nz = cz + d:1;
            if(abs(nx - rx) <= global_follow_search_radius && abs(nz - rz) <= global_follow_search_radius,
                candidate = __candidate_at(cy, nx, nz);
                if(candidate:'ok',
                    key = __pos_key(candidate:'pos');
                    if(!first(visited, _ == key),
                        visited += [key];
                        first_step = if(node_first, node_first, candidate:'pos');
                        depth = node_depth + 1;
                        distance = __flat_distance(candidate:'pos', owner);
                        score = distance + depth * 0.15;
                        if(!best || score < best_score,
                            best_score = score;
                            best = {
                                'ok' -> true,
                                'reason' -> 'best_effort',
                                'next' -> first_step,
                                'target' -> candidate:'pos',
                                'distance' -> distance,
                                'depth' -> depth,
                                'visited' -> length(visited)
                            }
                        );
                        if(distance <= global_follow_min_distance,
                            return({
                                'ok' -> true,
                                'reason' -> 'route',
                                'next' -> first_step,
                                'target' -> candidate:'pos',
                                'distance' -> distance,
                                'depth' -> depth,
                                'visited' -> length(visited)
                            })
                        );
                        if(depth < global_follow_search_radius,
                            frontier += [[candidate:'pos', first_step, depth]]
                        )
                    )
                )
            )
        )
    );
    if(best && best:'distance' <= start_distance + 1.5,
        return(best)
    );
    {'ok' -> false, 'reason' -> 'no_supported_route', 'distance' -> start_distance, 'visited' -> length(visited)}
);

__track_follow_progress(remy_pos) -> (
    if(!global_follow_last_pos,
        global_follow_last_pos = remy_pos;
        return(true)
    );
    moved = __distance3(remy_pos, global_follow_last_pos);
    if(moved < 0.03,
        global_follow_stuck_ticks += 1,
        global_follow_stuck_ticks = 0
    );
    global_follow_last_pos = remy_pos;
    if(global_follow_stuck_ticks > global_follow_stuck_limit,
        __stop_all('stuck');
        return(false)
    );
    true
);

__follow_safety(remy_pos, owner_pos) -> (
    [rx, ry, rz] = remy_pos;
    [ox, oy, oz] = owner_pos;
    dx = ox - rx;
    dz = oz - rz;
    flat = sqrt(dx * dx + dz * dz);
    if(flat < 0.001, return({'ok' -> true, 'reason' -> 'same_block'}));
    nx = floor(rx + dx / flat * 1.2);
    ny = floor(ry);
    nz = floor(rz + dz / flat * 1.2);
    foot = __block_name_at([nx, ny, nz]);
    head = __block_name_at([nx, ny + 1, nz]);
    below = __block_name_at([nx, ny - 1, nz]);
    if(__is_dangerous_name(foot) || __is_dangerous_name(head) || __is_dangerous_name(below),
        return({'ok' -> false, 'reason' -> 'hazard', 'foot' -> foot, 'head' -> head, 'below' -> below, 'pos' -> [nx, ny, nz]})
    );
    if(!__is_passable_name(foot),
        return({'ok' -> false, 'reason' -> 'blocked_feet', 'block' -> foot, 'pos' -> [nx, ny, nz]})
    );
    if(!__is_passable_name(head),
        return({'ok' -> false, 'reason' -> 'blocked_head', 'block' -> head, 'pos' -> [nx, ny + 1, nz]})
    );
    if(__is_passable_name(below),
        return({'ok' -> false, 'reason' -> 'cliff', 'block' -> below, 'pos' -> [nx, ny - 1, nz]})
    );
    {'ok' -> true, 'next' -> [nx, ny, nz], 'foot' -> foot, 'head' -> head, 'below' -> below}
);

__cancel_goal(reason) -> (
    global_goal_generation += 1;
    global_active_goal = 'idle';
    global_follow_active = false;
    global_follow_last_status = reason;
    global_follow_last_route = null;
    global_follow_last_pos = null;
    global_follow_stuck_ticks = 0
);

__stop_all(reason) -> (
    __cancel_goal(reason);
    __stop_remy()
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
        'activeGoal' -> global_active_goal,
        'goalGeneration' -> global_goal_generation,
        'followActive' -> global_follow_active,
        'followOwner' -> global_follow_owner_name,
        'followStatus' -> global_follow_last_status,
        'followRoute' -> global_follow_last_route,
        'followStuckTicks' -> global_follow_stuck_ticks,
        'runtimeSelfTest' -> global_runtime_self_test,
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

__spawn_at_owner(owner_name) -> (
    p = player(global_remy_name);
    if(p,
        if(!__remy_is_fake(p),
            return({'success' -> 0, 'error' -> 'refusing to use existing non-fake player named remy', 'playerType' -> __remy_player_type(p)})
        );
        return({'success' -> 1, 'alreadyOnline' -> true})
    );
    __command_result(run('execute as ' + owner_name + ' at @s run player ' + global_remy_name + ' spawn'))
);

__follow_start(owner_name) -> (
    owner = player(owner_name);
    if(!owner,
        return({'success' -> 0, 'error' -> 'owner is not online', 'owner' -> owner_name})
    );
    global_goal_generation += 1;
    my_generation = global_goal_generation;
    pre_stop = __stop_remy();
    if(!pre_stop:'success',
        __cancel_goal('follow_pre_stop_failed');
        return({'success' -> 0, 'error' -> 'pre-follow stop failed', 'stopResult' -> pre_stop})
    );
    spawn_result = __spawn_at_owner(owner_name);
    if(spawn_result:'success' == 0 || spawn_result:'error',
        __cancel_goal('spawn_failed');
        global_follow_last_status = 'spawn_failed';
        return(spawn_result)
    );
    global_active_goal = 'follow';
    global_follow_owner_name = owner_name;
    global_follow_active = true;
    global_follow_generation = my_generation;
    global_follow_last_status = 'following';
    global_follow_last_route = null;
    global_follow_last_pos = null;
    global_follow_stuck_ticks = 0;
    spawn_result:'followActive' = true;
    spawn_result:'goalGeneration' = my_generation;
    spawn_result
);

__follow_tick(now_ms) -> (
    if(!global_follow_active, return());
    generation = global_follow_generation;
    if(global_active_goal != 'follow' || generation != global_goal_generation,
        global_follow_active = false;
        return()
    );
    owner = player(global_follow_owner_name);
    remy = player(global_remy_name);
    if(!owner,
        __stop_all('owner_offline');
        return()
    );
    if(!__remy_is_fake(remy),
        __stop_all('remy_not_fake');
        return()
    );
    if(query(owner, 'dimension') != query(remy, 'dimension'),
        __stop_all('different_dimension');
        return()
    );
    owner_pos = query(owner, 'pos');
    remy_pos = query(remy, 'pos');
    [ox, oy, oz] = owner_pos;
    [rx, ry, rz] = remy_pos;
    dx = ox - rx;
    dy = oy - ry;
    dz = oz - rz;
    flat = sqrt(dx * dx + dz * dz);
    if(abs(dy) > global_follow_max_vertical,
        __stop_all('vertical_gap');
        return()
    );
    if(flat <= global_follow_min_distance,
        stop_result = __stop_remy();
        if(stop_result:'success',
            (
                global_follow_last_status = 'near_owner';
                global_follow_stuck_ticks = 0
            ),
            __stop_all('near_stop_failed')
        );
        return()
    );
    if(flat <= global_follow_resume_distance && global_follow_last_status == 'near_owner',
        __stop_remy();
        return()
    );
    if(flat > global_follow_max_distance,
        __stop_all('owner_too_far');
        return()
    );
    if(!__track_follow_progress(remy_pos),
        return()
    );
    route = __find_follow_step(remy_pos, owner_pos);
    global_follow_last_route = route;
    if(!route:'ok',
        __stop_all('blocked_' + route:'reason');
        return()
    );
    if(!route:'next',
        __stop_remy();
        global_follow_last_status = 'near_owner';
        return()
    );
    [tx, ty, tz] = route:'next';
    clear = __stop_remy();
    if(!clear:'success',
        __stop_all('clear_move_failed');
        return()
    );
    look = __command_result(__run_player('look at ' + (tx + 0.5) + ' ' + (ty + 1) + ' ' + (tz + 0.5)));
    if(!look:'success',
        __stop_all('look_failed');
        return()
    );
    move = __command_result(__run_player('move forward'));
    if(move:'success',
        global_is_moving = true;
        global_move_expires_ms = now_ms + 500;
        global_follow_last_status = 'following_' + route:'reason',
        __stop_all('move_failed')
    )
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
    __cancel_goal('manual_move');
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
        action == 'stop', __stop_all('manual_stop')
    );
    delete_file('command', 'json');
    status = if(detail:'success' == 0 || detail:'error', 'failed', 'ok');
    __ack(id, action, status, detail)
);

__poll_remy_bridge() -> (
    now_ms = unix_time();
    __enforce_move_expiry(now_ms);
    cmd = read_file('command', 'json');
    if(cmd,
        __execute(cmd, now_ms)
    );
    __follow_tick(now_ms);
    __flush_pending_ack();
    __maybe_heartbeat('tick')
);

__on_tick() -> (
    now_tick = tick_time();
    __enforce_move_expiry(unix_time());
    if(now_tick < global_next_poll_tick, return());
    global_next_poll_tick = now_tick + 5;
    __poll_remy_bridge()
);

__command() -> 'remy_bridge commands: /remy_bridge follow, /remy_bridge stop, /remy_bridge status';

__follow_command(p) -> (
    auth = __owner_check(p);
    if(!auth:'success', return(auth:'error'));
    result = __follow_start(auth:'ownerName');
    if(result:'success' == 0 || result:'error',
        'remy follow failed: ' + result:'error',
        'remy follow on'
    )
);

__stop_command(p) -> (
    auth = __owner_check(p);
    if(!auth:'success', return(auth:'error'));
    result = __stop_all('owner_stop');
    if(result:'success' == 0 || result:'error',
        'remy stop failed: ' + result:'error',
        'remy stopped'
    )
);

__status_command(p) -> (
    auth = __owner_check(p);
    if(!auth:'success', return(auth:'error'));
    __state()
);

follow() -> __follow_command(player());

stop() -> __stop_command(player());

status() -> __status_command(player());

__on_player_message(p, message) -> (
    if(!__is_owner(p), return());
    if(message == 'remy follow',
        print(p, __follow_command(p));
        return('cancel')
    );
    if(message == 'remy stop',
        print(p, __stop_command(p));
        return('cancel')
    );
    if(message == 'remy status',
        print(p, __status_command(p));
        return('cancel')
    )
);

__on_start() -> (
    __cancel_goal('reload');
    __stop_remy();
    global_runtime_self_test = __queue_shape_self_test();
    __heartbeat('start')
);

__on_close() -> (
    __stop_remy();
    __heartbeat('close')
);
