package com.sse.app.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "business_code_counters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BusinessCodeCounter {
    @Id
    @Column(name = "code_type", nullable = false, length = 50)
    private String codeType;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}
