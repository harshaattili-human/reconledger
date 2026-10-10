package dev.harshaattili.reconledger;

import static org.mockito.Mockito.*;

import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.postgresql.PGStatement;

class BatchBrowseStatementPolicyTest {
    @Test void disablesNamedPreparationOnlyWhenTheStatementWrapsPgJdbc() throws Exception {
        var postgres = mock(PreparedStatement.class);
        var extension = mock(PGStatement.class);
        when(postgres.isWrapperFor(PGStatement.class)).thenReturn(true);
        when(postgres.unwrap(PGStatement.class)).thenReturn(extension);

        BatchBrowseStatementPolicy.apply(postgres);

        verify(extension).setPrepareThreshold(0);

        var portable = mock(PreparedStatement.class);
        when(portable.isWrapperFor(PGStatement.class)).thenReturn(false);
        BatchBrowseStatementPolicy.apply(portable);
        verify(portable, never()).unwrap(PGStatement.class);
    }
}
