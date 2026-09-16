package com.sdebria.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigration implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    public DatabaseMigration(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public void run(String... args){
        addColumn("players","photo_data","TEXT");
        addColumn("players","photo_pos_x","REAL NOT NULL DEFAULT 50");
        addColumn("players","photo_pos_y","REAL NOT NULL DEFAULT 50");
        addColumn("players","photo_zoom","REAL NOT NULL DEFAULT 1");
        addColumn("matches","map_url","TEXT");
        addColumn("matches","lineup_payload","TEXT NOT NULL DEFAULT '{}'");
        addColumn("matches","finished_at","TEXT");
        addColumn("matches","started_at","TEXT");
        addColumn("match_player_stats","goals_conceded","INTEGER NOT NULL DEFAULT 0");
        addColumn("match_player_stats","clean_sheet","INTEGER NOT NULL DEFAULT 0");
        addColumn("match_player_stats","saves","INTEGER NOT NULL DEFAULT 0");
        addColumn("match_player_stats","goalkeeper_stats","INTEGER NOT NULL DEFAULT 1");
        addColumn("match_player_stats","starter","INTEGER NOT NULL DEFAULT 0");
        createTables();
        migrateRatingsToTen();
    }
    private void createTables(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS match_events (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, minute INTEGER NOT NULL DEFAULT 0, event_type TEXT NOT NULL, player_id INTEGER, assist_player_id INTEGER, notes TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE, FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE SET NULL, FOREIGN KEY(assist_player_id) REFERENCES players(id) ON DELETE SET NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS match_player_ratings (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, player_id INTEGER NOT NULL, rating REAL NOT NULL CHECK(rating >= 0 AND rating <= 10), UNIQUE(match_id, player_id), FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE, FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS match_votes (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, voter_player_id INTEGER NOT NULL, target_player_id INTEGER NOT NULL, vote_type TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(match_id, voter_player_id, vote_type), FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE, FOREIGN KEY(voter_player_id) REFERENCES players(id) ON DELETE CASCADE, FOREIGN KEY(target_player_id) REFERENCES players(id) ON DELETE CASCADE)");
    }

    private void migrateRatingsToTen(){
        try{
            String ddl=jdbc.queryForObject("SELECT sql FROM sqlite_master WHERE type='table' AND name='match_player_ratings'",String.class);
            if(ddl==null || !ddl.contains("9.99")) return;
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                try(java.sql.Statement st=con.createStatement()){
                    st.execute("PRAGMA foreign_keys=OFF");
                    st.execute("CREATE TABLE match_player_ratings_new (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id INTEGER NOT NULL, player_id INTEGER NOT NULL, rating REAL NOT NULL CHECK(rating >= 0 AND rating <= 10), UNIQUE(match_id, player_id), FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE, FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE CASCADE)");
                    st.execute("INSERT INTO match_player_ratings_new(id,match_id,player_id,rating) SELECT id,match_id,player_id,rating FROM match_player_ratings");
                    st.execute("DROP TABLE match_player_ratings");
                    st.execute("ALTER TABLE match_player_ratings_new RENAME TO match_player_ratings");
                    st.execute("PRAGMA foreign_keys=ON");
                }
                return null;
            });
        }catch(Exception ignored){ }
    }
    private void addColumn(String table,String column,String definition){
        try{jdbc.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);}catch(Exception ignored){ }
    }
}
