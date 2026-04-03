package br.com.angatusistemas.lib.gson;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

public class OffsetDateTimeTypeAdapter extends TypeAdapter<OffsetDateTime> {
    private final DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public void write(JsonWriter out, OffsetDateTime value) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(formatter.format(value));
        }
    }

    @Override
    public OffsetDateTime read(JsonReader in) throws IOException {
        if (in.peek() == null) {
            return null;
        }
        return OffsetDateTime.parse(in.nextString(), formatter);
    }
}