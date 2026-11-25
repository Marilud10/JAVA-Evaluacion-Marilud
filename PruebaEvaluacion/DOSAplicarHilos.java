// Punto 2: Aplicar Hilos y evidenciar/resolver una condición de carrera (race condition) en CarMotors.//

 //ARCHIVO NUEVO EN SERVICIOS, TALLER.

// SimuladorConcurrenciaTaller

package com.carmotors.servicio.taller;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Punto 2: Demostración de hilos y condición de carrera (race condition)
 * sobre un contador compartido de operaciones del taller, junto con su corrección.
 */
public class SimuladorConcurrenciaTaller {

    // Estado compartido NO sincronizado -> provoca race condition
    private int ordenesProcesadasSinSincronizar = 0;

    // Estado compartido protegido mediante cerrojo explícito (synchronized)
    private final Object cerrojo = new Object();
    private int ordenesProcesadasSincronizadas = 0;

    // Estado compartido seguro mediante operación atómica
    private final AtomicInteger ordenesProcesadasAtomicas = new AtomicInteger(0);

    public ResultadoConcurrencia ejecutarSimulacion(int numeroHilos, int operacionesPorHilo)
            throws InterruptedException {
        if (numeroHilos <= 0 || operacionesPorHilo <= 0) {
            throw new IllegalArgumentException("El número de hilos y operaciones debe ser mayor que cero");
        }

        ordenesProcesadasSinSincronizar = 0;
        ordenesProcesadasSincronizadas = 0;
        ordenesProcesadasAtomicas.set(0);

        int esperado = numeroHilos * operacionesPorHilo;

        // 1) Hilos con condición de carrera
        Thread[] hilosInseguros = new Thread[numeroHilos];
        for (int i = 0; i < numeroHilos; i++) {
            hilosInseguros[i] = new Thread(() -> {
                for (int j = 0; j < operacionesPorHilo; j++) {
                    int actual = ordenesProcesadasSinSincronizar;
                    Thread.yield(); // Favorece el cambio de contexto entre hilos
                    ordenesProcesadasSinSincronizar = actual + 1;
                }
            }, "Hilo-Taller-Race-" + i);
            hilosInseguros[i].start();
        }
        for (Thread hilo : hilosInseguros) {
            hilo.join();
        }

        // 2) Hilos sincronizados con bloque synchronized
        Thread[] hilosSincronizados = new Thread[numeroHilos];
        for (int i = 0; i < numeroHilos; i++) {
            hilosSincronizados[i] = new Thread(() -> {
                for (int j = 0; j < operacionesPorHilo; j++) {
                    synchronized (cerrojo) {
                        ordenesProcesadasSincronizadas++;
                    }
                }
            }, "Hilo-Taller-Sync-" + i);
            hilosSincronizados[i].start();
        }
        for (Thread hilo : hilosSincronizados) {
            hilo.join();
        }

        // 3) Hilos con AtomicInteger
        Thread[] hilosAtomicos = new Thread[numeroHilos];
        for (int i = 0; i < numeroHilos; i++) {
            hilosAtomicos[i] = new Thread(() -> {
                for (int j = 0; j < operacionesPorHilo; j++) {
                    ordenesProcesadasAtomicas.incrementAndGet();
                }
            }, "Hilo-Taller-Atomic-" + i);
            hilosAtomicos[i].start();
        }
        for (Thread hilo : hilosAtomicos) {
            hilo.join();
        }

        return new ResultadoConcurrencia(
                esperado,
                ordenesProcesadasSinSincronizar,
                ordenesProcesadasSincronizadas,
                ordenesProcesadasAtomicas.get()
        );
    }

    public int getOrdenesProcesadasSinSincronizar() {
        return ordenesProcesadasSinSincronizar;
    }

    public int getOrdenesProcesadasSincronizadas() {
        return ordenesProcesadasSincronizadas;
    }

    public int getOrdenesProcesadasAtomicas() {
        return ordenesProcesadasAtomicas.get();
    }

    public record ResultadoConcurrencia(
            int totalEsperado,
            int resultadoConRaceCondition,
            int resultadoSincronizado,
            int resultadoAtomico
    ) {
    }
}


// ServicioControlador editar si te toca este.

package com.carmotors.controlador;

import com.carmotors.modelo.Servicio;
import com.carmotors.modelo.ServicioTecnico;
import com.carmotors.modelo.Tecnico;
import com.carmotors.servicio.taller.ServicioService;
import com.carmotors.servicio.taller.SimuladorConcurrenciaTaller;
import com.carmotors.servicio.taller.dominio.IdentificadorServicio;
import com.carmotors.servicio.taller.integracion.ConsumoRepuesto;
import com.carmotors.servicio.taller.integracion.ReservaRepuesto;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Punto de entrada del módulo de Servicios para la futura vista.
 * No contiene SQL: valida/delega los casos de uso en ServicioService.
 */
public final class ServicioControlador {
    private final ServicioService servicio;

    public ServicioControlador() {
        this(new ServicioService());
    }

    public ServicioControlador(ServicioService servicio) {
        this.servicio = Objects.requireNonNull(servicio);
    }

    public IdentificadorServicio registrarServicio(Servicio solicitud) throws SQLException {
        return servicio.registrarServicio(solicitud);
    }

    public Optional<Servicio> buscarServicio(int id) {
        return servicio.buscarPorId(id);
    }

    public List<Servicio> listarServicios() {
        return servicio.listar();
    }

    public void actualizarEstado(int servicioId, int estadoId) throws SQLException {
        servicio.actualizarEstado(servicioId, estadoId);
    }

    public void reservarRepuesto(ReservaRepuesto solicitud) throws SQLException {
        servicio.reservarRepuesto(solicitud);
    }

    public void consumirRepuesto(ConsumoRepuesto solicitud) throws SQLException {
        servicio.consumirRepuesto(solicitud);
    }

    public void liberarRepuesto(ReservaRepuesto solicitud) throws SQLException {
        servicio.liberarRepuesto(solicitud);
    }

    public void actualizarFechaEntrega(int servicioId, LocalDateTime fechaEntrega) throws SQLException {
        servicio.actualizarFechaEntrega(servicioId, fechaEntrega);
    }

    public int registrarTecnico(Tecnico solicitud) throws SQLException {
        return servicio.registrarTecnico(solicitud);
    }

    public Optional<Tecnico> buscarTecnico(int id) throws SQLException {
        return servicio.buscarTecnico(id);
    }

    public List<Tecnico> listarTecnicos() throws SQLException {
        return servicio.listarTecnicos();
    }

    public List<Tecnico> listarTecnicosActivos() throws SQLException {
        return servicio.listarTecnicosActivos();
    }

    public boolean actualizarTecnicoActivo(int id, boolean activo) throws SQLException {
        return servicio.actualizarTecnicoActivo(id, activo);
    }

    public void asignarTecnico(ServicioTecnico asignacion) throws SQLException {
        servicio.asignarTecnico(asignacion);
    }

    // --- NUEVO REPORTE: Servicios completados (Punto 1) ---
    public List<Servicio> listarServiciosCompletados() throws SQLException {
        return servicio.listarServiciosCompletados();
    }

    // --- HILOS Y RACE CONDITION (Punto 2) ---
    public SimuladorConcurrenciaTaller.ResultadoConcurrencia ejecutarSimulacionConcurrencia(
            int numeroHilos, int operacionesPorHilo) throws InterruptedException {
        return new SimuladorConcurrenciaTaller().ejecutarSimulacion(numeroHilos, operacionesPorHilo);
    }
}