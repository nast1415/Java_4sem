package ru.spbau.mit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Created by Анастасия on 11.04.2016.
 */
public class PartsBitset {
    private int cnt = 0;
    private boolean[] flags;

    public PartsBitset(int size, boolean defaultValue) {
        flags = new boolean[size];
        if (defaultValue) {
            Arrays.fill(flags, true);
            cnt = size;
        }
    }

    public boolean get(int pos) {
        return flags[pos];
    }

    public void set(int pos, boolean value) {
        if (flags[pos] == value) {
            return;
        }
        flags[pos] = !flags[pos];
        if (flags[pos]) {
            cnt++;
        } else {
            cnt--;
        }
    }

    public int getCount() {
        return cnt;
    }

    public void writeDataToOutputStream(DataOutputStream outputStream) throws IOException {
        outputStream.writeInt(cnt);
        for (int i = 0; i != flags.length; i++) {
            if (get(i)) {
                outputStream.writeInt(i);
            }
        }
    }

    public static PartsBitset readDataFromInputStream(DataInputStream inputStream, int size) throws IOException {
        PartsBitset result = new PartsBitset(size, false);
        int count = inputStream.readInt();
        while (count > 0) {
            count--;
            result.set(inputStream.readInt(), true);
        }
        return result;
    }

    public void subtract(PartsBitset other) {
        assert (other.flags.length == flags.length);
        for (int i = 0; i != flags.length; i++) {
            if (other.get(i)) {
                set(i, false);
            }
        }
    }

    public int getFirstBitAtLeast(int pos) {
        for (int i = pos; i != flags.length; i++) {
            if (get(i)) {
                return i;
            }
        }
        return -1;
    }
}
