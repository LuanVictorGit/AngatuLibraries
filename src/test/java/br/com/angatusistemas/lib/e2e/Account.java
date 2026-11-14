package br.com.angatusistemas.lib.e2e;

import br.com.angatusistemas.lib.database.Saveable;

/**
 * Account record of the end-to-end application: a balance moved by transfers and a counter raised
 * by concurrent increments, so lost updates show up as wrong totals.
 *
 * @author Angatu Sistemas
 */
public class Account extends Saveable {

    private String id;
    private String email;
    private long balance;
    private long counter;

    public Account() {
    }

    Account(String id, String email, long balance) {
        this.id = id;
        this.email = email;
        this.balance = balance;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public long getBalance() {
        return balance;
    }

    public long getCounter() {
        return counter;
    }

    void addBalance(long amount) {
        balance += amount;
    }

    void increment() {
        counter++;
    }
}
