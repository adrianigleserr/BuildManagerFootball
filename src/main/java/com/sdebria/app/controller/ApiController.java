package com.sdebria.app.controller;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate jdbc;
    private static final String ADMIN_USER = "Administrador";
    private static final String ADMIN_PASSWORD = "aaaaAAAA1234";
    private final java.util.concurrent.ConcurrentMap<String, Boolean> adminSessions = new java.util.concurrent.ConcurrentHashMap<>();

    public ApiController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping("/auth/login")
    public Map<String,Object> login(@RequestBody LoginRequest req) {
        if (ADMIN_USER.equals(req.username()) && ADMIN_PASSWORD.equals(req.password())) {
            String token = UUID.randomUUID().toString();
            adminSessions.put(token, Boolean.TRUE);
            return Map.of("ok", true, "username", ADMIN_USER, "token", token);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos");
    }

    @PostMapping("/auth/logout")
    public Map<String,Object> logout(@RequestHeader(value="Authorization", required=false) String authorization) {
        String token = bearerToken(authorization);
        if (token != null) adminSessions.remove(token);
        return Map.of("ok", true);
    }

    @GetMapping("/auth/me")
    public Map<String,Object> me(@RequestHeader(value="Authorization", required=false) String authorization) {
        boolean admin = isAdmin(authorization);
        return Map.of("authenticated", admin, "username", admin ? ADMIN_USER : "Invitado");
    }

    private void requireAdmin(String authorization) {
        if (!isAdmin(authorization)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el Administrador puede modificar la aplicación");
    }
    private boolean isAdmin(String authorization) {
        String token = bearerToken(authorization);
        return token != null && adminSessions.containsKey(token);
    }
    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        String token = authorization.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    @GetMapping("/players")
    public List<Map<String, Object>> players() {
        return jdbc.queryForList("""
            SELECT p.id, p.real_name, p.nickname, p.shirt_number, p.position, p.active, p.photo_data, p.photo_pos_x, p.photo_pos_y, p.photo_zoom,
                   CASE WHEN p.position='ENTRENADOR' THEN COALESCE(c.coach_matches,0) ELSE COALESCE(s.matches_played,0) END matches_played,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.minutes,0) END minutes,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.goals,0) END goals,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.assists,0) END assists,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.big_mistakes,0) END big_mistakes,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.yellow_cards,0) END yellow_cards,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.red_cards,0) END red_cards,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.fouls,0) END fouls,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.goals_conceded,0) END goals_conceded,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.clean_sheets,0) END clean_sheets,
                   CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(s.saves,0) END saves,
                   COALESCE(a.botellines,0) botellines, COALESCE(a.chapas,0) chapas,
                   COALESCE(a.entrenador_awards,0) entrenador_awards,
                   COALESCE(c.wins,0) coach_wins, COALESCE(c.draws,0) coach_draws, COALESCE(c.losses,0) coach_losses,
                   COALESCE(c.goal_average,0) coach_goal_average, COALESCE(ravg.avg_rating,0) average_rating
            FROM players p
            LEFT JOIN (
                SELECT player_id, COUNT(*) matches_played, SUM(minutes) minutes, SUM(goals) goals,
                       SUM(assists) assists, SUM(big_mistakes) big_mistakes, SUM(yellow_cards) yellow_cards,
                       SUM(red_cards) red_cards, SUM(fouls) fouls, SUM(goals_conceded) goals_conceded,
                       SUM(clean_sheet) clean_sheets, SUM(saves) saves
                FROM match_player_stats GROUP BY player_id
            ) s ON s.player_id=p.id
            LEFT JOIN (
                SELECT player_id,
                       SUM(CASE WHEN award_type='BOTELLIN' THEN 1 ELSE 0 END) botellines,
                       SUM(CASE WHEN award_type='CHAPA' THEN 1 ELSE 0 END) chapas,
                       SUM(CASE WHEN award_type='ENTRENADOR' THEN 1 ELSE 0 END) entrenador_awards
                FROM awards GROUP BY player_id
            ) a ON a.player_id=p.id
            LEFT JOIN (
                SELECT player_id, AVG(rating) avg_rating FROM match_player_ratings GROUP BY player_id
            ) ravg ON ravg.player_id=p.id
            LEFT JOIN (
                SELECT a.player_id, COUNT(*) coach_matches,
                       SUM(CASE WHEN m.home_score>m.away_score THEN 1 ELSE 0 END) wins,
                       SUM(CASE WHEN m.home_score=m.away_score THEN 1 ELSE 0 END) draws,
                       SUM(CASE WHEN m.home_score<m.away_score THEN 1 ELSE 0 END) losses,
                       SUM(m.home_score-m.away_score) goal_average
                FROM awards a JOIN matches m ON m.id=a.match_id
                WHERE a.award_type='ENTRENADOR' AND m.status='FINISHED'
                GROUP BY a.player_id
            ) c ON c.player_id=p.id
            ORDER BY CASE p.position WHEN 'ENTRENADOR' THEN 0 WHEN 'PORTERO' THEN 1 WHEN 'DEFENSA' THEN 2 WHEN 'LATERAL' THEN 3 WHEN 'MEDIOCENTRO' THEN 4 WHEN 'DELANTERO' THEN 5 ELSE 6 END, p.shirt_number, p.nickname
            """);
    }

    @GetMapping("/players/{id}/photo")
    public ResponseEntity<byte[]> playerPhoto(@PathVariable long id) {
        try {
            String data = jdbc.queryForObject("SELECT photo_data FROM players WHERE id=?", String.class, id);
            if (data == null || data.isBlank()) return ResponseEntity.notFound().build();
            String mime = "image/jpeg";
            int comma = data.indexOf(',');
            String base64 = data;
            if (data.startsWith("data:") && comma > 0) {
                String meta = data.substring(5, comma);
                int semi = meta.indexOf(';');
                mime = semi > 0 ? meta.substring(0, semi) : meta;
                base64 = data.substring(comma + 1);
            }
            byte[] bytes = Base64.getDecoder().decode(base64);
            MediaType mediaType;
            try { mediaType = MediaType.parseMediaType(mime); }
            catch (Exception ignored) { mediaType = MediaType.IMAGE_JPEG; }
            return ResponseEntity.ok().contentType(mediaType).body(bytes);
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "La foto guardada no es válida");
        }
    }

    @GetMapping("/players/{id}")
    public Map<String, Object> player(@PathVariable long id) {
        try {
            Map<String, Object> result = jdbc.queryForMap("""
                SELECT p.id,p.real_name,p.nickname,p.shirt_number,p.position,p.active,p.photo_data,p.photo_pos_x,p.photo_pos_y,p.photo_zoom,
                       CASE WHEN p.position='ENTRENADOR' THEN COALESCE(c.coach_matches,0) ELSE COUNT(DISTINCT s.id) END matches_played,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.minutes),0) END minutes,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.goals),0) END goals,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.assists),0) END assists,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.big_mistakes),0) END big_mistakes,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.yellow_cards),0) END yellow_cards,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.red_cards),0) END red_cards,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.fouls),0) END fouls,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.goals_conceded),0) END goals_conceded,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.clean_sheet),0) END clean_sheets,
                       CASE WHEN p.position='ENTRENADOR' THEN 0 ELSE COALESCE(SUM(s.saves),0) END saves,
                       COALESCE(c.wins,0) coach_wins,COALESCE(c.draws,0) coach_draws,COALESCE(c.losses,0) coach_losses,COALESCE(c.goal_average,0) coach_goal_average, COALESCE(ravg.avg_rating,0) average_rating
                FROM players p LEFT JOIN match_player_stats s ON s.player_id=p.id
                LEFT JOIN (SELECT player_id,AVG(rating) avg_rating FROM match_player_ratings GROUP BY player_id) ravg ON ravg.player_id=p.id
                LEFT JOIN (
                  SELECT a.player_id, COUNT(*) coach_matches,
                         SUM(CASE WHEN m.home_score>m.away_score THEN 1 ELSE 0 END) wins,
                         SUM(CASE WHEN m.home_score=m.away_score THEN 1 ELSE 0 END) draws,
                         SUM(CASE WHEN m.home_score<m.away_score THEN 1 ELSE 0 END) losses,
                         SUM(m.home_score-m.away_score) goal_average
                  FROM awards a JOIN matches m ON m.id=a.match_id
                  WHERE a.award_type='ENTRENADOR' AND m.status='FINISHED' GROUP BY a.player_id
                ) c ON c.player_id=p.id
                WHERE p.id=? GROUP BY p.id
                """, id);
            result.put("awards", jdbc.queryForList("SELECT award_type, COUNT(*) total FROM awards WHERE player_id=? GROUP BY award_type", id));
            result.put("ratingsHistory", jdbc.queryForList("SELECT r.rating,m.match_date,m.opponent FROM match_player_ratings r JOIN matches m ON m.id=r.match_id WHERE r.player_id=? ORDER BY m.match_date DESC,m.match_time DESC",id));
            if ("ENTRENADOR".equals(String.valueOf(result.get("position")))) {
                result.put("history", jdbc.queryForList("""
                    SELECT m.id match_id,m.match_date,m.match_time,m.opponent,m.home_score,m.away_score
                    FROM awards a JOIN matches m ON m.id=a.match_id
                    WHERE a.player_id=? AND a.award_type='ENTRENADOR'
                    ORDER BY m.match_date DESC,m.match_time DESC
                    """, id));
            } else {
                result.put("history", jdbc.queryForList("""
                    SELECT m.id match_id,m.match_date,m.match_time,m.opponent,m.home_score,m.away_score,
                           s.minutes,s.goals,s.assists,s.big_mistakes,s.yellow_cards,s.red_cards,s.fouls,
                           s.goals_conceded,s.clean_sheet,s.saves,s.goalkeeper_stats
                    FROM match_player_stats s JOIN matches m ON m.id=s.match_id
                    WHERE s.player_id=? ORDER BY m.match_date DESC,m.match_time DESC
                    """, id));
            }
            return result;
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Jugador no encontrado");
        }
    }

    @PostMapping("/players")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addPlayer(@RequestBody PlayerRequest p, @RequestHeader(value="Authorization", required=false) String authorization) {
        requireAdmin(authorization);
        validatePlayer(p);
        if ("ENTRENADOR".equals(p.position())) ensureSingleCoach(null);
        return jdbc.queryForMap("""
            INSERT INTO players(real_name,nickname,shirt_number,position,active,photo_data,photo_pos_x,photo_pos_y,photo_zoom)
            VALUES(?,?,?,?,1,?,?,?,?) RETURNING id,real_name,nickname,shirt_number,position,active,photo_data,photo_pos_x,photo_pos_y,photo_zoom
            """, p.realName(),p.nickname(),p.shirtNumber(),p.position(),p.photoData(),p.photoPosX(),p.photoPosY(),p.photoZoom());
    }

    @PutMapping("/players/{id}")
    public void updatePlayer(@PathVariable long id,@RequestBody PlayerRequest p, @RequestHeader(value="Authorization", required=false) String authorization) {
        requireAdmin(authorization);
        validatePlayer(p);
        if ("ENTRENADOR".equals(p.position())) ensureSingleCoach(id);
        int updated=jdbc.update("UPDATE players SET real_name=?,nickname=?,shirt_number=?,position=?,photo_data=?,photo_pos_x=?,photo_pos_y=?,photo_zoom=? WHERE id=?",
                p.realName(),p.nickname(),p.shirtNumber(),p.position(),p.photoData(),p.photoPosX(),p.photoPosY(),p.photoZoom(),id);
        if(updated==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Jugador no encontrado");
    }

    @DeleteMapping("/players/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlayer(@PathVariable long id, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization); jdbc.update("DELETE FROM awards WHERE player_id=?",id); jdbc.update("DELETE FROM match_player_stats WHERE player_id=?",id); jdbc.update("DELETE FROM match_player_ratings WHERE player_id=?",id); jdbc.update("DELETE FROM match_votes WHERE voter_player_id=? OR target_player_id=?",id,id); jdbc.update("DELETE FROM match_events WHERE player_id=? OR assist_player_id=?",id,id); jdbc.update("DELETE FROM players WHERE id=?",id); }

    @GetMapping("/matches")
    public List<Map<String,Object>> matches(){
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT m.*,
              (SELECT p.nickname FROM awards a JOIN players p ON p.id=a.player_id WHERE a.match_id=m.id AND a.award_type='BOTELLIN') botellin,
              (SELECT p.nickname FROM awards a JOIN players p ON p.id=a.player_id WHERE a.match_id=m.id AND a.award_type='CHAPA') chapa,
              (SELECT p.nickname FROM awards a JOIN players p ON p.id=a.player_id WHERE a.match_id=m.id AND a.award_type='ENTRENADOR') entrenador
            FROM matches m ORDER BY match_date DESC,match_time DESC
            """);
        for(Map<String,Object> m:rows){
            String date=String.valueOf(m.get("match_date")); String time=String.valueOf(m.get("match_time"));
            m.put("overdue", "SCHEDULED".equals(m.get("status")) && isPast(date,time));
            if(m.get("map_url")==null || String.valueOf(m.get("map_url")).isBlank()) m.put("map_url",mapsUrl(String.valueOf(m.get("location"))));
        }
        return rows;
    }

    @PostMapping("/matches")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addMatch(@RequestBody MatchRequest m, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization);
        if(blank(m.date())||blank(m.time())||blank(m.location())||blank(m.opponent())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Completa fecha, hora, lugar y rival");
        String map=blank(m.mapUrl())?mapsUrl(m.location()):m.mapUrl();
        return jdbc.queryForMap("""
            INSERT INTO matches(match_date,match_time,location,opponent,home_score,away_score,season,status,map_url,lineup_payload)
            VALUES(?,?,?,?,NULL,NULL,?, 'SCHEDULED',?,?) RETURNING *
            """,m.date(),m.time(),m.location(),m.opponent(),blank(m.season())?"2026/27":m.season(),map,blank(m.lineupPayload())?"{}":m.lineupPayload());
    }

    @PutMapping("/matches/{id}/lineup")
    public void saveLineup(@PathVariable long id,@RequestBody LineupRequest req, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization);ensureMatch(id);jdbc.update("UPDATE matches SET lineup_payload=? WHERE id=?",blank(req.payload())?"{}":req.payload(),id);}

    @PostMapping("/matches/{id}/start")
    public Map<String,Object> startMatch(@PathVariable long id, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization);
        ensureMatch(id);
        String status=jdbc.queryForObject("SELECT status FROM matches WHERE id=?",String.class,id);
        if("FINISHED".equals(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El partido ya ha terminado");
        String startedAt=jdbc.queryForObject("SELECT started_at FROM matches WHERE id=?",String.class,id);
        if(startedAt==null || startedAt.isBlank()){
            startedAt=java.time.OffsetDateTime.now().toString();
            jdbc.update("UPDATE matches SET status='LIVE', started_at=? WHERE id=?",startedAt,id);
        } else {
            jdbc.update("UPDATE matches SET status='LIVE' WHERE id=?",id);
        }
        return Map.of("ok",true,"startedAt",startedAt,"status","LIVE");
    }

    @GetMapping("/matches/{id}")
    public Map<String,Object> match(@PathVariable long id){
        Map<String,Object> m=jdbc.queryForMap("SELECT * FROM matches WHERE id=?",id);
        m.put("stats",jdbc.queryForList("""
            SELECT s.*,p.nickname,p.shirt_number,p.position,p.photo_data FROM match_player_stats s JOIN players p ON p.id=s.player_id
            WHERE s.match_id=? ORDER BY s.minutes DESC,p.shirt_number
            """,id));
        m.put("awards",jdbc.queryForList("SELECT a.award_type,a.player_id,p.nickname FROM awards a JOIN players p ON p.id=a.player_id WHERE a.match_id=?",id));
        m.put("events",jdbc.queryForList("SELECT e.*,p.nickname player_nickname,p.shirt_number player_number,ap.nickname assist_nickname FROM match_events e LEFT JOIN players p ON p.id=e.player_id LEFT JOIN players ap ON ap.id=e.assist_player_id WHERE e.match_id=? ORDER BY e.minute,e.id",id));
        m.put("ratings",jdbc.queryForList("SELECT r.player_id,r.rating,p.nickname,p.shirt_number,COALESCE(s.minutes,0) minutes FROM match_player_ratings r JOIN players p ON p.id=r.player_id LEFT JOIN match_player_stats s ON s.match_id=r.match_id AND s.player_id=r.player_id WHERE r.match_id=? ORDER BY r.rating DESC,COALESCE(s.minutes,0) ASC,p.shirt_number ASC",id));
        m.put("voting",votingSummary(id));
        return m;
    }

    @PutMapping("/matches/{id}")
    public void updateMatch(@PathVariable long id,@RequestBody MatchRequest m, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization);
        ensureMatch(id); if(blank(m.date())||blank(m.time())||blank(m.location())||blank(m.opponent())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Completa los datos del partido");
        jdbc.update("UPDATE matches SET match_date=?,match_time=?,location=?,opponent=?,map_url=?,season=? WHERE id=?",m.date(),m.time(),m.location(),m.opponent(),blank(m.mapUrl())?mapsUrl(m.location()):m.mapUrl(),blank(m.season())?"2026/27":m.season(),id);
    }

    @DeleteMapping("/matches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMatch(@PathVariable long id, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization);
        ensureMatch(id);
        // Explicit cleanup keeps statistics/awards from surviving even if SQLite foreign-key enforcement is disabled in a connection.
        jdbc.update("DELETE FROM awards WHERE match_id=?",id);
        jdbc.update("DELETE FROM match_player_stats WHERE match_id=?",id);
        jdbc.update("DELETE FROM match_events WHERE match_id=?",id);
        jdbc.update("DELETE FROM match_player_ratings WHERE match_id=?",id);
        jdbc.update("DELETE FROM match_votes WHERE match_id=?",id);
        jdbc.update("DELETE FROM matches WHERE id=?",id);
    }

    @PostMapping("/matches/{id}/finish")
    public Map<String,Object> finishMatch(@PathVariable long id,@RequestBody FinishMatchRequest r, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization);
        ensureMatch(id);
        if(r.homeScore()==null||r.awayScore()==null||r.homeScore()<0||r.awayScore()<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Resultado no válido");
        String lineupPayload=r.lineupPayload()!=null?r.lineupPayload():jdbc.queryForObject("SELECT lineup_payload FROM matches WHERE id=?",String.class,id);
        Set<Long> convokedIds=extractConvokedPlayerIds(lineupPayload);
        if(convokedIds.isEmpty() && r.stats()!=null && r.stats().stream().anyMatch(PlayerStatRequest::played)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Primero debes guardar la convocatoria del partido");
        }
        if(r.stats()!=null) for(PlayerStatRequest s:r.stats()) if(s.played() && !convokedIds.contains(s.playerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Solo los jugadores convocados pueden tener estadísticas en este partido");
        }
        String finishedAt = blank(r.finishedAt()) ? java.time.OffsetDateTime.now().toString() : r.finishedAt();
        jdbc.update("UPDATE matches SET home_score=?,away_score=?,status='FINISHED',finished_at=? WHERE id=?",r.homeScore(),r.awayScore(),finishedAt,id);
        jdbc.update("DELETE FROM match_player_stats WHERE match_id=?",id); jdbc.update("DELETE FROM awards WHERE match_id=?",id);
        if(r.stats()!=null) for(PlayerStatRequest s:r.stats()) if(s.played()) {
            if(isCoach(s.playerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El entrenador no puede aparecer como jugador del partido");
            // El acta grande permite corregir cualquier estadística manualmente.
            // Al abrirla, los campos con eventos se rellenan automáticamente desde el acta;
            // al guardar, se conserva la corrección manual introducida por el Administrador.
            int manualGoals=Math.max(0,nz(s.goals()));
            int manualAssists=Math.max(0,nz(s.assists()));
            int manualYellow=Math.max(0,nz(s.yellowCards()));
            int manualRed=Math.max(0,nz(s.redCards()));
            int manualFouls=Math.max(0,nz(s.fouls()));
            boolean keeper=isGoalkeeper(s.playerId());
            // Los goles encajados de cada portero se introducen manualmente en el acta.
            // No se puede asumir que todos los porteros hayan jugado los mismos minutos.
            boolean goalkeeperStats=keeper && s.goalkeeperStats();
            int goalsConceded=goalkeeperStats?Math.max(0,nz(s.goalsConceded())):0;
            int cleanSheet=goalkeeperStats&&goalsConceded==0?1:0;
            int saves=goalkeeperStats?Math.max(0,nz(s.saves())):0;
            jdbc.update("""
                INSERT INTO match_player_stats(match_id,player_id,minutes,goals,assists,big_mistakes,yellow_cards,red_cards,fouls,goals_conceded,clean_sheet,saves,goalkeeper_stats,starter)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,s.playerId(),nz(s.minutes()),manualGoals,manualAssists,nz(s.bigMistakes()),manualYellow,manualRed,manualFouls,goalsConceded,cleanSheet,saves,goalkeeperStats?1:0,s.starter()?1:0);
        }
        saveAward(id,"BOTELLIN",r.botellinPlayerId(),true);
        saveAward(id,"CHAPA",r.chapaPlayerId(),true);
        saveCoachAward(id,r.entrenadorPlayerId());
        jdbc.update("DELETE FROM match_player_ratings WHERE match_id=?",id);
        if(r.ratings()!=null) for(RatingRequest rating:r.ratings()){
            if(rating.rating()==null || rating.rating()<0 || rating.rating()>10.0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Las notas deben estar entre 0,00 y 10,00");
            if(!playedInMatch(id,rating.playerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Solo se puede puntuar a jugadores que jugaron");
            jdbc.update("INSERT INTO match_player_ratings(match_id,player_id,rating) VALUES(?,?,?)",id,rating.playerId(),Math.round(rating.rating()*100.0)/100.0);
        }
        if(r.lineupPayload()!=null) jdbc.update("UPDATE matches SET lineup_payload=? WHERE id=?",r.lineupPayload(),id);
        return Map.of("ok",true);
    }

    private int eventCount(long matchId,long playerId,String type){Integer c=jdbc.queryForObject("SELECT COUNT(*) FROM match_events WHERE match_id=? AND player_id=? AND event_type=?",Integer.class,matchId,playerId,type);return nz(c);}
    private int assistCount(long matchId,long playerId){Integer c=jdbc.queryForObject("SELECT COUNT(*) FROM match_events WHERE match_id=? AND assist_player_id=? AND event_type='GOAL_FOR'",Integer.class,matchId,playerId);return nz(c);}

    private void saveAward(long matchId,String type,Long playerId,boolean mustHavePlayed){
        if(playerId==null)return;
        if(isCoach(playerId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El entrenador no puede ser Botellín ni Chapa");
        if(mustHavePlayed && !playedInMatch(matchId,playerId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Botellín y Chapa solo pueden ser jugadores marcados como Jugó");
        jdbc.update("INSERT INTO awards(match_id,player_id,award_type) VALUES(?,?,?)",matchId,playerId,type);
    }
    private void saveCoachAward(long matchId,Long playerId){
        if(playerId==null)return;
        if(!isCoach(playerId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Entrenador del partido solo puede ser un jugador marcado como Entrenador");
        jdbc.update("INSERT INTO awards(match_id,player_id,award_type) VALUES(?,?,?)",matchId,playerId,"ENTRENADOR");
    }
    private boolean playedInMatch(long matchId,long playerId){Integer c=jdbc.queryForObject("SELECT COUNT(*) FROM match_player_stats WHERE match_id=? AND player_id=?",Integer.class,matchId,playerId);return c!=null&&c>0;}
    private Set<Long> extractConvokedPlayerIds(String payload){
        Set<Long> ids=new HashSet<>();
        if(payload==null)return ids;
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(payload);
        while(matcher.find()){try{ids.add(Long.parseLong(matcher.group(1)));}catch(Exception ignored){}}
        return ids;
    }
    private boolean isCoach(long playerId){Integer c=jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE id=? AND position='ENTRENADOR'",Integer.class,playerId);return c!=null&&c>0;}
    private boolean isGoalkeeper(long playerId){Integer c=jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE id=? AND position='PORTERO'",Integer.class,playerId);return c!=null&&c>0;}
    private void recalculateEventScore(long matchId){
        Integer home=jdbc.queryForObject("SELECT COUNT(*) FROM match_events WHERE match_id=? AND event_type='GOAL_FOR'",Integer.class,matchId);
        Integer away=jdbc.queryForObject("SELECT COUNT(*) FROM match_events WHERE match_id=? AND event_type IN ('GOAL_AGAINST','OWN_GOAL')",Integer.class,matchId);
        jdbc.update("UPDATE matches SET home_score=?,away_score=? WHERE id=?",nz(home),nz(away),matchId);
    }

    private void recalculateEventStats(long matchId){
        Map<String,Object> match=jdbc.queryForMap("SELECT home_score,away_score FROM matches WHERE id=?",matchId);
        int away=nz(((Number)match.get("away_score")).intValue());
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT player_id FROM match_player_stats WHERE match_id=?",matchId);
        for(Map<String,Object> row:rows){
            long pid=((Number)row.get("player_id")).longValue();
            int goals=eventCount(matchId,pid,"GOAL_FOR"), assists=assistCount(matchId,pid), yellow=eventCount(matchId,pid,"YELLOW"), red=eventCount(matchId,pid,"RED"), fouls=eventCount(matchId,pid,"FOUL");
            int saves=eventCount(matchId,pid,"SAVE")+eventCount(matchId,pid,"PENALTY_SAVED");
            // Los eventos actualizan las estadísticas que representan directamente.
            // Goles encajados y porterías a 0 siguen siendo datos manuales del portero.
            jdbc.update("UPDATE match_player_stats SET goals=?,assists=?,yellow_cards=?,red_cards=?,fouls=?,saves=CASE WHEN goalkeeper_stats=1 AND ? > 0 THEN ? ELSE saves END WHERE match_id=? AND player_id=?",goals,assists,yellow,red,fouls,saves,saves,matchId,pid);
        }
    }


    private boolean isFinishedMatch(long id){String status=jdbc.queryForObject("SELECT status FROM matches WHERE id=?",String.class,id);return "FINISHED".equals(status);}

    @GetMapping("/matches/{id}/events")
    public List<Map<String,Object>> matchEvents(@PathVariable long id){
        ensureMatch(id);
        return jdbc.queryForList("SELECT e.*,p.nickname player_nickname,p.shirt_number player_number,ap.nickname assist_nickname FROM match_events e LEFT JOIN players p ON p.id=e.player_id LEFT JOIN players ap ON ap.id=e.assist_player_id WHERE e.match_id=? ORDER BY e.minute,e.id",id);
    }

    @PostMapping("/matches/{id}/events")
    public Map<String,Object> addMatchEvent(@PathVariable long id,@RequestBody MatchEventRequest e,@RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization); ensureMatch(id);
        if(e.minute()==null || e.minute()<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Minuto no válido");
        Set<String> types=Set.of("GOAL_FOR","GOAL_AGAINST","GOAL_DISALLOWED","YELLOW","RED","OWN_GOAL","PENALTY_AGAINST","PENALTY_FOR","PENALTY_SAVED","SAVE","INJURY","FOUL");
        if(!types.contains(e.eventType())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo de evento no válido");
        Set<Long> convoked=extractConvokedPlayerIds(jdbc.queryForObject("SELECT lineup_payload FROM matches WHERE id=?",String.class,id));
        if(e.playerId()!=null && !convoked.contains(e.playerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El jugador debe estar convocado");
        if(e.assistPlayerId()!=null && !convoked.contains(e.assistPlayerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El asistente debe estar convocado");
        if(e.playerId()!=null && isCoach(e.playerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El entrenador no puede ser jugador del acta");
        if(e.assistPlayerId()!=null && isCoach(e.assistPlayerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El entrenador no puede ser asistente");
        if(!"GOAL_FOR".equals(e.eventType()) && e.assistPlayerId()!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La asistencia solo existe en un gol a favor");
        if("GOAL_AGAINST".equals(e.eventType()) && e.playerId()!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El gol en contra usa automáticamente el equipo rival");
        Map<String,Object> saved=jdbc.queryForMap("INSERT INTO match_events(match_id,minute,event_type,player_id,assist_player_id,notes) VALUES(?,?,?,?,?,?) RETURNING id,match_id,minute,event_type,player_id,assist_player_id,notes,created_at",id,e.minute(),e.eventType(),e.playerId(),e.assistPlayerId(),e.notes());
        recalculateEventScore(id);
        if(isFinishedMatch(id)) recalculateEventStats(id);
        return saved;
    }

    @PutMapping("/matches/{id}/events/{eventId}")
    public Map<String,Object> updateMatchEvent(@PathVariable long id,@PathVariable long eventId,@RequestBody MatchEventRequest e,@RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization); ensureMatch(id);
        if(e.minute()==null || e.minute()<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Minuto no válido");
        Set<String> types=Set.of("GOAL_FOR","GOAL_AGAINST","GOAL_DISALLOWED","YELLOW","RED","OWN_GOAL","PENALTY_AGAINST","PENALTY_FOR","PENALTY_SAVED","SAVE","INJURY","FOUL");
        if(!types.contains(e.eventType())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo de evento no válido");
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM match_events WHERE id=? AND match_id=?",Integer.class,eventId,id);
        if(exists==null||exists==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Evento no encontrado");
        String payload=jdbc.queryForObject("SELECT lineup_payload FROM matches WHERE id=?",String.class,id);
        Set<Long> convoked=extractConvokedPlayerIds(payload);
        if(e.playerId()!=null && !convoked.contains(e.playerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El jugador debe estar convocado");
        if(e.assistPlayerId()!=null && !convoked.contains(e.assistPlayerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El asistente debe estar convocado");
        if(e.playerId()!=null && isCoach(e.playerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El entrenador no puede ser jugador del acta");
        if(e.assistPlayerId()!=null && isCoach(e.assistPlayerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El entrenador no puede ser asistente");
        if(!"GOAL_FOR".equals(e.eventType()) && e.assistPlayerId()!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La asistencia solo existe en un gol a favor");
        if("GOAL_AGAINST".equals(e.eventType()) && e.playerId()!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El gol en contra usa automáticamente el equipo rival");
        Map<String,Object> saved=jdbc.queryForMap("UPDATE match_events SET minute=?,event_type=?,player_id=?,assist_player_id=?,notes=? WHERE id=? AND match_id=? RETURNING id,match_id,minute,event_type,player_id,assist_player_id,notes,created_at",e.minute(),e.eventType(),e.playerId(),e.assistPlayerId(),e.notes(),eventId,id);
        recalculateEventScore(id);
        if(isFinishedMatch(id)) recalculateEventStats(id);
        return saved;
    }

    @DeleteMapping("/matches/{id}/events/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMatchEvent(@PathVariable long id,@PathVariable long eventId,@RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization); ensureMatch(id); jdbc.update("DELETE FROM match_events WHERE id=? AND match_id=?",eventId,id); recalculateEventScore(id); if(isFinishedMatch(id)) recalculateEventStats(id);
    }

    @PostMapping("/matches/{id}/ratings")
    public Map<String,Object> saveRatings(@PathVariable long id,@RequestBody RatingsRequest req,@RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization); ensureMatch(id);
        for(RatingRequest r:req.ratings()==null?List.<RatingRequest>of():req.ratings()){
            if(r.rating()==null || r.rating()<0 || r.rating()>10.0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Las notas deben estar entre 0,00 y 10,00");
            Integer played=jdbc.queryForObject("SELECT COUNT(*) FROM match_player_stats WHERE match_id=? AND player_id=?",Integer.class,id,r.playerId());
            if(played==null || played==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Solo se puede puntuar a jugadores que jugaron");
            jdbc.update("INSERT INTO match_player_ratings(match_id,player_id,rating) VALUES(?,?,?) ON CONFLICT(match_id,player_id) DO UPDATE SET rating=excluded.rating",id,r.playerId(),Math.round(r.rating()*100.0)/100.0);
        }
        return Map.of("ok",true);
    }

    @GetMapping("/matches/{id}/voting")
    public Map<String,Object> voting(@PathVariable long id){ ensureMatch(id); return votingSummary(id); }

    @PostMapping("/matches/{id}/votes")
    public Map<String,Object> vote(@PathVariable long id,@RequestBody VoteRequest req){
        ensureMatch(id);
        Map<String,Object> match=jdbc.queryForMap("SELECT status,finished_at FROM matches WHERE id=?",id);
        if(!"FINISHED".equals(String.valueOf(match.get("status")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La votación aún no está abierta");
        String finished=String.valueOf(match.get("finished_at"));
        try{LocalDateTime end=parseFinishedAt(finished).plusHours(2); if(LocalDateTime.now().isAfter(end)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La votación ya está cerrada");}catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Hora de finalización no válida");}
        if(req.voterPlayerId()==null||req.targetPlayerId()==null||blank(req.voteType())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Voto incompleto");
        if(!Set.of("BOTELLIN","CHAPA","ENTRENADOR").contains(req.voteType())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo de votación no válido");
        if(!playedInMatch(id,req.voterPlayerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Solo pueden votar jugadores que hayan jugado");
        if("BOTELLIN".equals(req.voteType())||"CHAPA".equals(req.voteType())){
            if(req.voterPlayerId().equals(req.targetPlayerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No puedes votarte a ti mismo");
            if(!playedInMatch(id,req.targetPlayerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El candidato debe haber jugado");
        }else if(!isCoach(req.targetPlayerId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El candidato debe ser el entrenador");
        try{jdbc.update("INSERT INTO match_votes(match_id,voter_player_id,target_player_id,vote_type) VALUES(?,?,?,?)",id,req.voterPlayerId(),req.targetPlayerId(),req.voteType());}
        catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Ya has votado en esta categoría");}
        return votingSummary(id);
    }

    private Map<String,Object> votingSummary(long id){
        Map<String,Object> out=new LinkedHashMap<>();
        String finished=null; try{finished=jdbc.queryForObject("SELECT finished_at FROM matches WHERE id=?",String.class,id);}catch(Exception ignored){}
        long end=0; boolean open=false;
        if(finished!=null&&!finished.isBlank()) try{end=parseFinishedAt(finished).plusHours(2).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();open=System.currentTimeMillis()<end;}catch(Exception ignored){}
        out.put("open",open);out.put("finishedAt",finished);out.put("endsAt",end);
        List<Map<String,Object>> played=jdbc.queryForList("SELECT p.id,p.nickname,p.shirt_number FROM match_player_stats s JOIN players p ON p.id=s.player_id WHERE s.match_id=? ORDER BY p.shirt_number",id);
        out.put("players",played); out.put("voters",jdbc.queryForList("SELECT voter_player_id,vote_type FROM match_votes WHERE match_id=?",id));
        out.put("botellin",voteResults(id,"BOTELLIN",3)); out.put("chapa",voteResults(id,"CHAPA",1)); out.put("entrenador",voteResults(id,"ENTRENADOR",1));
        return out;
    }
    private List<Map<String,Object>> voteResults(long id,String type,int limit){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT p.id,p.nickname,p.shirt_number,COUNT(v.id) total FROM match_votes v JOIN players p ON p.id=v.target_player_id WHERE v.match_id=? AND v.vote_type=? GROUP BY p.id ORDER BY total DESC,p.nickname LIMIT ?",id,type,limit);
        Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM match_votes WHERE match_id=? AND vote_type=?",Integer.class,id,type); int t=nz(total);
        for(Map<String,Object> r:rows) r.put("percentage",t==0?0:Math.round(((Number)r.get("total")).doubleValue()*10000.0/t)/100.0);
        return rows;
    }
    private LocalDateTime parseFinishedAt(String value){
        if(value==null) throw new IllegalArgumentException();
        try{return LocalDateTime.parse(value.replace("Z",""),DateTimeFormatter.ISO_LOCAL_DATE_TIME);}catch(Exception ignored){}
        return java.time.OffsetDateTime.parse(value).toLocalDateTime();
    }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard(){
        Map<String,Object> out=new LinkedHashMap<>();
        Integer played=jdbc.queryForObject("SELECT COUNT(*) FROM matches WHERE status='FINISHED'",Integer.class);
        Integer wins=jdbc.queryForObject("SELECT COUNT(*) FROM matches WHERE status='FINISHED' AND home_score>away_score",Integer.class);
        Integer draws=jdbc.queryForObject("SELECT COUNT(*) FROM matches WHERE status='FINISHED' AND home_score=away_score",Integer.class);
        Integer losses=jdbc.queryForObject("SELECT COUNT(*) FROM matches WHERE status='FINISHED' AND home_score<away_score",Integer.class);
        Integer gf=jdbc.queryForObject("SELECT COALESCE(SUM(home_score),0) FROM matches WHERE status='FINISHED'",Integer.class);
        Integer ga=jdbc.queryForObject("SELECT COALESCE(SUM(away_score),0) FROM matches WHERE status='FINISHED'",Integer.class);
        out.put("played",nz(played));out.put("wins",nz(wins));out.put("draws",nz(draws));out.put("losses",nz(losses));out.put("goalsFor",nz(gf));out.put("goalsAgainst",nz(ga));out.put("goalAverage",nz(gf)-nz(ga));
        List<Map<String,Object>> next=jdbc.queryForList("SELECT * FROM matches WHERE status='SCHEDULED' ORDER BY match_date ASC,match_time ASC");
        next.removeIf(m -> isPast(String.valueOf(m.get("match_date")),String.valueOf(m.get("match_time"))));
        out.put("next",next.isEmpty()?null:next.getFirst());
        return out;
    }

    @GetMapping("/stats")
    public Map<String,Object> stats(){
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("goals",leaderboard("SUM(s.goals)"));r.put("assists",leaderboard("SUM(s.assists)"));r.put("minutes",leaderboard("SUM(s.minutes)"));
        r.put("mistakes",leaderboard("SUM(s.big_mistakes)"));r.put("yellow",leaderboard("SUM(s.yellow_cards)"));r.put("red",leaderboard("SUM(s.red_cards)"));
        r.put("botellines",awardLeaderboard("BOTELLIN"));r.put("chapas",awardLeaderboard("CHAPA"));r.put("cleanSheets",goalkeeperLeaderboard("SUM(s.clean_sheet)"));r.put("goalsConceded",goalkeeperLeaderboard("SUM(s.goals_conceded)"));r.put("saves",goalkeeperLeaderboard("SUM(s.saves)"));
        r.put("averagePerMatch",averageRatingLeaderboard());
        return r;
    }
    private List<Map<String,Object>> averageRatingLeaderboard(){
        return jdbc.queryForList("SELECT p.id,p.nickname,p.shirt_number,ROUND(AVG(r.rating),2) total FROM match_player_ratings r JOIN players p ON p.id=r.player_id WHERE p.position<>'ENTRENADOR' GROUP BY p.id ORDER BY total DESC,p.nickname LIMIT 20");
    }
    private List<Map<String,Object>> leaderboard(String expression){return jdbc.queryForList("SELECT p.id,p.nickname,p.shirt_number,"+expression+" total FROM players p JOIN match_player_stats s ON s.player_id=p.id WHERE p.position<>'ENTRENADOR' GROUP BY p.id ORDER BY total DESC,p.nickname LIMIT 20");}
    private List<Map<String,Object>> awardLeaderboard(String type){return jdbc.queryForList("SELECT p.id,p.nickname,p.shirt_number,COUNT(*) total FROM awards a JOIN players p ON p.id=a.player_id WHERE a.award_type=? AND p.position<>'ENTRENADOR' GROUP BY p.id ORDER BY total DESC,p.nickname LIMIT 20",type);}
    private List<Map<String,Object>> goalkeeperLeaderboard(String expression){return jdbc.queryForList("SELECT p.id,p.nickname,p.shirt_number,"+expression+" total FROM players p JOIN match_player_stats s ON s.player_id=p.id WHERE p.position='PORTERO' GROUP BY p.id ORDER BY total DESC,p.nickname LIMIT 20");}

    @GetMapping("/tactics") public List<Map<String,Object>> tactics(){return jdbc.queryForList("SELECT id,name,payload,updated_at FROM tactics ORDER BY updated_at DESC");}
    @PostMapping("/tactics") public Map<String,Object> saveTactic(@RequestBody TacticRequest t, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization);if(blank(t.name())||blank(t.payload()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nombre y táctica son obligatorios");jdbc.update("INSERT INTO tactics(name,payload,updated_at) VALUES(?,?,CURRENT_TIMESTAMP) ON CONFLICT(name) DO UPDATE SET payload=excluded.payload,updated_at=CURRENT_TIMESTAMP",t.name(),t.payload());return jdbc.queryForMap("SELECT id,name,payload,updated_at FROM tactics WHERE name=?",t.name());}
    @DeleteMapping("/tactics/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteTactic(@PathVariable long id, @RequestHeader(value="Authorization", required=false) String authorization){
        requireAdmin(authorization);jdbc.update("DELETE FROM tactics WHERE id=?",id);}

    private void ensureSingleCoach(Long currentId){Integer c=currentId==null?jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE position='ENTRENADOR'",Integer.class):jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE position='ENTRENADOR' AND id<>?",Integer.class,currentId);if(c!=null&&c>0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Solo puede existir un Entrenador en la plantilla");}
    private void ensureMatch(long id){Integer c=jdbc.queryForObject("SELECT COUNT(*) FROM matches WHERE id=?",Integer.class,id);if(c==null||c==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Partido no encontrado");}
    private void validatePlayer(PlayerRequest p){if(blank(p.realName())||blank(p.nickname())||blank(p.position())||p.shirtNumber()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Completa todos los datos del jugador");if(!Set.of("ENTRENADOR","PORTERO","DEFENSA","LATERAL","MEDIOCENTRO","DELANTERO").contains(p.position()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Posición no válida");}
    private boolean blank(String s){return s==null||s.isBlank();}
    private int nz(Integer i){return i==null?0:Math.max(0,i);}
    private boolean isPast(String d,String t){try{return LocalDate.parse(d).isBefore(LocalDate.now()) || (LocalDate.parse(d).isEqual(LocalDate.now()) && t.compareTo(java.time.LocalTime.now().toString().substring(0,5))<0);}catch(Exception e){return false;}}
    private String mapsUrl(String location){return "https://www.google.com/maps/search/?api=1&query="+URLEncoder.encode(location,StandardCharsets.UTF_8);}

    public record PlayerRequest(String realName,String nickname,Integer shirtNumber,String position,String photoData,Double photoPosX,Double photoPosY,Double photoZoom){}
    public record MatchRequest(String date,String time,String location,String opponent,String season,String mapUrl,String lineupPayload){}
    public record LineupRequest(String payload){}
    public record PlayerStatRequest(Long playerId,boolean played,boolean starter,Integer minutes,Integer goals,Integer assists,Integer bigMistakes,Integer yellowCards,Integer redCards,Integer fouls,Integer goalsConceded,boolean cleanSheet,Integer saves,boolean goalkeeperStats){}
    public record FinishMatchRequest(Integer homeScore,Integer awayScore,List<PlayerStatRequest> stats,Long botellinPlayerId,Long chapaPlayerId,Long entrenadorPlayerId,String lineupPayload,String finishedAt,List<RatingRequest> ratings){}
    public record MatchEventRequest(Integer minute,String eventType,Long playerId,Long assistPlayerId,String notes){}
    public record RatingRequest(Long playerId,Double rating){}
    public record RatingsRequest(List<RatingRequest> ratings){}
    public record VoteRequest(Long voterPlayerId,Long targetPlayerId,String voteType){}
    public record TacticRequest(String name,String payload){}
    public record LoginRequest(String username,String password){}
}
