package br.com.angatusistemas.lib.e2e;

import br.com.angatusistemas.lib.database.Saveable;

/**
 * Note record of the end-to-end application: it has no id until saved and overrides save(), the
 * way projects stamp a timestamp before writing, so batching must never skip the override.
 *
 * @author Angatu Sistemas
 */
public class Note extends Saveable {

    private String id;
    private String text;
    private long savedAt;

    public Note() {
    }

    Note(String text) {
        this.text = text;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean save() {
        savedAt = System.currentTimeMillis();
        return super.save();
    }

    public String getText() {
        return text;
    }

    public long getSavedAt() {
        return savedAt;
    }
}
