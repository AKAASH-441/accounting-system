package com.accounting.model;

import java.time.LocalDateTime;

public class Customer {
    private final Long id;                 // null until saved
    private String name;
    private String phone;
    private String email;
    private final LocalDateTime createdAt; // null until saved

    /** A brand-new customer (id and createdAt are assigned by the database). */
    public Customer(String name, String phone, String email) {
        this(null, name, phone, email, null);
    }

    /** A customer loaded from the database. */
    public Customer(Long id, String name, String phone, String email, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.createdAt = createdAt;
    }

    public Long getId()                  { return id; }
    public String getName()              { return name; }
    public String getPhone()             { return phone; }
    public String getEmail()             { return email; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    public void setName(String name)     { this.name = name; }
    public void setPhone(String phone)   { this.phone = phone; }
    public void setEmail(String email)   { this.email = email; }

    @Override
    public String toString() { return "Customer#" + id + " " + name; }
}
