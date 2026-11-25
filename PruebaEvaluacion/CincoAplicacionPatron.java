// l Punto 5: Aplicación del Patrón Observer para notificaciones del cliente (conectado con el patrón comportamental que ya trabajaste en Java).

// Crear un archivo nuevo en Servicio.Taller con el nombre ObservadorNotificacionCliente

package com.carmotors.servicio.taller;

import com.carmotors.modelo.Servicio;

/**
 * Punto 5 (Patrón Observer): Contrato para observadores que reaccionan
 * ante eventos de una orden de servicio para notificar al cliente.
 */
@FunctionalInterface
public interface ObservadorNotificacionCliente {
    void alActualizarServicio(Servicio servicio, String evento, String detalle);
}

// Tambien va en servicio.taller,  Esto e un archivo nuevo y se va a llamar NotificadorConsolaCliente

package com.carmotors.servicio.taller;

import com.carmotors.modelo.Servicio;

import java.io.PrintStream;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Punto 5 (Patrón Observer): Observador concreto que emite notificaciones
 * legibles para el cliente cuando cambia o se actualiza su orden de servicio.
 */
public final class NotificadorConsolaCliente implements ObservadorNotificacionCliente {
    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private final PrintStream salida;

    public NotificadorConsolaCliente() {
        this(System.out);
    }

    public NotificadorConsolaCliente(PrintStream salida) {
        this.salida = Objects.requireNonNull(salida, "El flujo de salida es obligatorio");
    }

    @Override
    public void alActualizarServicio(Servicio servicio, String evento, String detalle) {
        Objects.requireNonNull(servicio, "El servicio no puede ser nulo");
        String recepcion = servicio.fechaRecepcion() != null
                ? servicio.fechaRecepcion().format(FORMATO_FECHA)
                : "sin fecha";
        String entrega = servicio.fechaEntrega() != null
                ? servicio.fechaEntrega().format(FORMATO_FECHA)
                : "sin fecha";

        salida.printf(
                "[NOTIFICACIÓN CLIENTE] Evento: %s | Orden #%d (Vehículo ID: %d) | %s | Recepción: %s | Entrega: %s%n",
                evento,
                servicio.id(),
                servicio.vehiculoId(),
                detalle,
                recepcion,
                entrega
        );
    }
}


// Servicio.Taller va en el archivo ServicioService

package com.carmotors.servicio.taller;

import com.carmotors.config.ConexionFactory;
import com.carmotors.dao.FuenteConexion;
import com.carmotors.dao.ServicioDAO;
import com.carmotors.dao.TecnicoDAO;
import com.carmotors.modelo.Servicio;
import com.carmotors.modelo.ServicioTecnico;
import com.carmotors.modelo.Tecnico;
import com.carmotors.servicio.taller.dominio.IdentificadorServicio;
import com.carmotors.servicio.taller.dominio.IdentificadorVehiculo;
import com.carmotors.servicio.taller.integracion.ConsumoRepuesto;
import com.carmotors.servicio.taller.integracion.InventarioServiciosPort;
import com.carmotors.servicio.taller.integracion.ReservaRepuesto;
import com.carmotors.servicios.inventario.InventarioServiciosJdbc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class ServicioService {
    private static final DateTimeFormatter FORMATO_REPORTE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private final ServicioDAO servicios;
    private final TecnicoDAO tecnicos;
    private final FuenteConexion fuente;
    private final ConsultaOrdenServicio<IdentificadorServicio, Servicio> consulta;
    private final List<ObservadorNotificacionCliente> observadores = new ArrayList<>();

    public ServicioService() {
        this(new ServicioDAO(), new TecnicoDAO());
    }

    public ServicioService(ServicioDAO servicios, TecnicoDAO tecnicos) {
        this(servicios, tecnicos, ConexionFactory::obtenerConexion);
    }

    public ServicioService(ServicioDAO servicios, TecnicoDAO tecnicos, FuenteConexion fuente) {
        this.servicios = Objects.requireNonNull(servicios);
        this.tecnicos = Objects.requireNonNull(tecnicos);
        this.fuente = Objects.requireNonNull(fuente);
        this.consulta = new ConsultaOrdenServicio<>(servicios);
    }

    public IdentificadorServicio registrarServicio(Servicio solicitud) throws SQLException {
        validarAlta(solicitud);
        return servicios.registrar(solicitud);
    }

    public IdentificadorServicio registrarServicioConReservas(Servicio solicitud, List<RepuestoSolicitado> repuestos)
            throws SQLException {
        validarAlta(solicitud);
        List<RepuestoSolicitado> lineas = List.copyOf(repuestos).stream()
                .sorted(Comparator.comparingInt(RepuestoSolicitado::repuestoId)).toList();
        return enTransaccion((conexion, inventario) -> {
            if (!lineas.isEmpty()) {
                validarOrdenOperativa(nombreEstado(conexion, solicitud.estadoId()));
            }
            IdentificadorServicio id = servicios.registrar(conexion, solicitud);
            for (RepuestoSolicitado linea : lineas) {
                inventario.reservar(new ReservaRepuesto(Math.toIntExact(id.valor()),
                        linea.repuestoId(), linea.cantidad()));
            }
            return id;
        });
    }

    public record RepuestoSolicitado(int repuestoId, int cantidad) {
        public RepuestoSolicitado {
            validarId(repuestoId, "repuesto");
            if (cantidad <= 0) {
                throw new IllegalArgumentException("La cantidad debe ser positiva");
            }
        }
    }

    private static void validarAlta(Servicio solicitud) {
        if (solicitud == null || solicitud.id() != null || solicitud.fechaRecepcion() == null) {
            throw new IllegalArgumentException("El alta requiere fecha de recepción y un ID por generar");
        }
        new IdentificadorVehiculo(solicitud.vehiculoId());
        validarId(solicitud.mantenimientoTipoId(), "tipo de mantenimiento");
        validarId(solicitud.estadoId(), "estado");
        if (solicitud.descripcionProblema() == null || solicitud.descripcionProblema().isBlank()) {
            throw new IllegalArgumentException("La descripción del problema es obligatoria");
        }
        if (solicitud.costoManoObra() != null && solicitud.costoManoObra().signum() < 0) {
            throw new IllegalArgumentException("El costo de mano de obra debe ser no negativo");
        }
        validarFechaEntrega(solicitud.fechaRecepcion(), solicitud.fechaEntrega());
    }

    public Optional<Servicio> buscarPorId(int id) {
        return consulta.buscarPorId(new IdentificadorServicio(id));
    }

    public List<Servicio> listar() {
        return consulta.listar();
    }

    public void actualizarEstado(int id, int estadoId) throws SQLException {
        IdentificadorServicio servicioId = new IdentificadorServicio(id);
        validarId(estadoId, "estado");
        String destino = enTransaccion((conexion, inventario) -> {
            bloquearOrden(conexion, servicioId);
            String nombreDestino = nombreEstado(conexion, estadoId);
            if (cancelado(nombreDestino)) {
                inventario.liberarTodo(id);
            }
            servicios.actualizarEstado(conexion, servicioId, estadoId);
            return nombreDestino;
        });
        buscarPorId(id).ifPresent(ordenActualizada ->
                notificarObservadores(ordenActualizada, "CAMBIO_ESTADO",
                        "Nuevo estado: " + destino + " (ID " + estadoId + ")"));
    }

    public void reservarRepuesto(ReservaRepuesto solicitud) throws SQLException {
        moverRepuesto(solicitud.servicioId(), false, inventario -> inventario.reservar(solicitud));
    }

    public void consumirRepuesto(ConsumoRepuesto solicitud) throws SQLException {
        moverRepuesto(solicitud.servicioId(), true, inventario -> inventario.consumir(solicitud));
    }

    public void liberarRepuesto(ReservaRepuesto solicitud) throws SQLException {
        moverRepuesto(solicitud.servicioId(), false, inventario -> inventario.liberar(solicitud));
    }

    private void moverRepuesto(int id, boolean consumo, Consumer<InventarioServiciosPort> movimiento)
            throws SQLException {
        enTransaccion((conexion, inventario) -> {
            var orden = new IdentificadorServicio(id);
            int estadoActual = bloquearOrden(conexion, orden);
            validarOrdenOperativa(nombreEstado(conexion, estadoActual));
            if (consumo && servicios.tieneFactura(conexion, orden)) {
                throw new IllegalStateException("La orden ya está facturada. No se puede modificar su consumo.");
            }
            movimiento.accept(inventario);
            return null;
        });
    }

    private int bloquearOrden(Connection conexion, IdentificadorServicio id) throws SQLException {
        return servicios.bloquearEstadoActual(conexion, id)
                .orElseThrow(() -> servicioInexistente(Math.toIntExact(id.valor())));
    }

    private String nombreEstado(Connection conexion, int estadoId) throws SQLException {
        return servicios.buscarNombreEstado(conexion, estadoId)
                .orElseThrow(() -> new NoSuchElementException("No existe el estado " + estadoId));
    }

    private static boolean cancelado(String estado) {
        return "Cancelado".equalsIgnoreCase(estado.strip()) || "Cancelada".equalsIgnoreCase(estado.strip());
    }

    private static void validarOrdenOperativa(String estado) {
        if (cancelado(estado)) {
            throw new IllegalStateException("La orden está cancelada");
        }
    }

    private <T> T enTransaccion(OperacionInventario<T> operacion) throws SQLException {
        try (Connection conexion = fuente.obtener()) {
            conexion.setAutoCommit(false);
            try {
                T resultado = operacion.ejecutar(conexion, new InventarioServiciosJdbc(conexion));
                conexion.commit();
                return resultado;
            } catch (SQLException | RuntimeException | Error error) {
                try {
                    conexion.rollback();
                } catch (SQLException falloRollback) {
                    error.addSuppressed(falloRollback);
                }
                throw error;
            }
        }
    }

    @FunctionalInterface
    private interface OperacionInventario<T> {
        T ejecutar(Connection conexion, InventarioServiciosJdbc inventario) throws SQLException;
    }

    public void actualizarFechaEntrega(int id, LocalDateTime fechaEntrega) throws SQLException {
        Servicio servicio = buscarPorId(id).orElseThrow(() -> servicioInexistente(id));
        validarFechaEntrega(servicio.fechaRecepcion(), fechaEntrega);
        if (!servicios.actualizarFechaEntrega(new IdentificadorServicio(id), fechaEntrega)) {
            throw servicioInexistente(id);
        }
        buscarPorId(id).ifPresent(ordenActualizada ->
                notificarObservadores(ordenActualizada, "ACTUALIZACION_ENTREGA",
                        fechaEntrega == null
                                ? "Fecha de entrega removida"
                                : "Fecha de entrega actualizada a " + fechaEntrega.format(FORMATO_REPORTE)));
    }

    public int registrarTecnico(Tecnico solicitud) throws SQLException {
        if (solicitud == null || solicitud.id() != null
                || solicitud.nombre() == null || solicitud.nombre().isBlank()) {
            throw new IllegalArgumentException("El alta de un técnico requiere nombre y un ID por generar");
        }
        return tecnicos.registrar(new Tecnico(null, solicitud.nombre().strip(),
                solicitud.especialidad(), solicitud.activo()));
    }

    public Optional<Tecnico> buscarTecnico(int id) throws SQLException {
        validarId(id, "técnico");
        return tecnicos.buscarPorId(id);
    }

    public List<Tecnico> listarTecnicos() throws SQLException {
        return tecnicos.listar();
    }

    public List<Tecnico> listarTecnicosActivos() throws SQLException {
        return tecnicos.listarActivos();
    }

    public boolean actualizarTecnicoActivo(int id, boolean activo) throws SQLException {
        validarId(id, "técnico");
        return tecnicos.actualizarActivo(id, activo);
    }

    public void asignarTecnico(ServicioTecnico asignacion) throws SQLException {
        if (asignacion == null || asignacion.fechaAsignacion() == null) {
            throw new IllegalArgumentException("La fecha de asignación es obligatoria");
        }
        int servicioId = asignacion.servicioId();
        int tecnicoId = asignacion.tecnicoId();
        IdentificadorServicio id = new IdentificadorServicio(servicioId);
        validarId(tecnicoId, "técnico");
        Tecnico tecnico = tecnicos.buscarPorId(tecnicoId)
                .orElseThrow(() -> new NoSuchElementException("No existe el técnico " + tecnicoId));
        if (!tecnico.activo()) {
            throw new IllegalStateException("El técnico debe estar activo para asignarlo");
        }
        consulta.buscarPorId(id).orElseThrow(() -> servicioInexistente(servicioId));
        if (!servicios.asignarTecnico(asignacion)) {
            throw new IllegalStateException("El técnico dejó de estar disponible para la asignación");
        }
    }

    private static void validarId(int id, String entidad) {
        if (id <= 0) {
            throw new IllegalArgumentException("El identificador de " + entidad + " debe ser positivo");
        }
    }

    private static void validarFechaEntrega(LocalDateTime recepcion, LocalDateTime entrega) {
        if (entrega != null && entrega.isBefore(recepcion)) {
            throw new IllegalArgumentException("La fecha de entrega es anterior a la recepción");
        }
    }

    private static NoSuchElementException servicioInexistente(int id) {
        return new NoSuchElementException("No existe el servicio " + id);
    }

    // --- NUEVO REPORTE: Servicios completados (Punto 1) ---
    public List<Servicio> listarServiciosCompletados() throws SQLException {
        return List.copyOf(servicios.listarCompletados());
    }

    // --- AJUSTE DE CONSULTA SQL CON NUEVA CONDICIÓN (Punto 3) ---
    public List<Servicio> listarPorRangoEntrega(LocalDateTime desde, LocalDateTime hasta) throws SQLException {
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException("La fecha final no puede ser anterior a la fecha inicial");
        }
        return List.copyOf(servicios.listar(desde, hasta));
    }

    // --- EXPORTACIÓN DE REPORTE DETALLADO EN FORMATO .TXT (Punto 4) ---
    public Path exportarReporteServiciosCompletadosTxt(Path rutaArchivo) throws SQLException, IOException {
        Objects.requireNonNull(rutaArchivo, "La ruta del archivo es obligatoria");
        List<Servicio> completados = listarServiciosCompletados();
        Path destino = rutaArchivo.toString().toLowerCase().endsWith(".txt")
                ? rutaArchivo
                : Path.of(rutaArchivo.toString() + ".txt");
        Path padre = destino.toAbsolutePath().getParent();
        if (padre != null) {
            Files.createDirectories(padre);
        }
        try (BufferedWriter escritor = Files.newBufferedWriter(destino, StandardCharsets.UTF_8)) {
            escritor.write("==========================================================");
            escritor.newLine();
            escritor.write("CARMOTORS - REPORTE DETALLADO DE SERVICIOS COMPLETADOS");
            escritor.newLine();
            escritor.write("Generado: " + LocalDateTime.now().format(FORMATO_REPORTE));
            escritor.newLine();
            escritor.write("==========================================================");
            escritor.newLine();
            if (completados.isEmpty()) {
                escritor.write("No hay servicios completados registrados.");
                escritor.newLine();
            } else {
                for (Servicio orden : completados) {
                    escritor.write(String.format(
                            "Servicio #%d | Vehículo ID: %d | Tipo Mant.: %d | Estado ID: %d%n"
                                    + "  Fecha recepción: %s%n"
                                    + "  Fecha entrega:   %s%n"
                                    + "  Mano de obra:    %s%n"
                                    + "  Descripción:     %s%n"
                                    + "----------------------------------------------------------%n",
                            orden.id(),
                            orden.vehiculoId(),
                            orden.mantenimientoTipoId(),
                            orden.estadoId(),
                            orden.fechaRecepcion() == null ? "sin fecha" : orden.fechaRecepcion().format(FORMATO_REPORTE),
                            orden.fechaEntrega() == null ? "sin fecha" : orden.fechaEntrega().format(FORMATO_REPORTE),
                            orden.costoManoObra() == null ? "sin registrar" : orden.costoManoObra().toPlainString(),
                            orden.descripcionProblema()
                    ));
                }
            }
            escritor.write("Total servicios completados: " + completados.size());
            escritor.newLine();
        }
        return destino;
    }

    // --- PATRÓN OBSERVER PARA NOTIFICACIONES DEL CLIENTE (Punto 5) ---
    public void registrarObservador(ObservadorNotificacionCliente observador) {
        observadores.add(Objects.requireNonNull(observador, "El observador no puede ser nulo"));
    }

    public boolean eliminarObservador(ObservadorNotificacionCliente observador) {
        return observadores.remove(observador);
    }

    private void notificarObservadores(Servicio orden, String evento, String detalle) {
        for (ObservadorNotificacionCliente observador : List.copyOf(observadores)) {
            observador.alActualizarServicio(orden, evento, detalle);
        }
    }
}


// ServicioControlador

package com.carmotors.controlador;

import com.carmotors.modelo.Servicio;
import com.carmotors.modelo.ServicioTecnico;
import com.carmotors.modelo.Tecnico;
import com.carmotors.servicio.taller.ObservadorNotificacionCliente;
import com.carmotors.servicio.taller.ServicioService;
import com.carmotors.servicio.taller.SimuladorConcurrenciaTaller;
import com.carmotors.servicio.taller.dominio.IdentificadorServicio;
import com.carmotors.servicio.taller.integracion.ConsumoRepuesto;
import com.carmotors.servicio.taller.integracion.ReservaRepuesto;

import java.io.IOException;
import java.nio.file.Path;
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

    // --- AJUSTE DE CONSULTA SQL CON NUEVA CONDICIÓN (Punto 3) ---
    public List<Servicio> listarPorRangoEntrega(LocalDateTime desde, LocalDateTime hasta) throws SQLException {
        return servicio.listarPorRangoEntrega(desde, hasta);
    }

    // --- EXPORTACIÓN DE REPORTE DETALLADO EN FORMATO .TXT (Punto 4) ---
    public Path exportarReporteServiciosCompletadosTxt(Path rutaArchivo) throws SQLException, IOException {
        return servicio.exportarReporteServiciosCompletadosTxt(rutaArchivo);
    }

    // --- PATRÓN OBSERVER PARA NOTIFICACIONES DEL CLIENTE (Punto 5) ---
    public void registrarObservador(ObservadorNotificacionCliente observador) {
        servicio.registrarObservador(observador);
    }

    public boolean eliminarObservador(ObservadorNotificacionCliente observador) {
        return servicio.eliminarObservador(observador);
    }
}


//VistaServicios

package com.carmotors.vista;

import com.carmotors.controlador.ServicioControlador;
import com.carmotors.dao.StockInsuficienteException;
import com.carmotors.modelo.Servicio;
import com.carmotors.modelo.ServicioTecnico;
import com.carmotors.modelo.Tecnico;
import com.carmotors.servicio.taller.NotificadorConsolaCliente;
import com.carmotors.servicio.taller.integracion.ConsumoRepuesto;
import com.carmotors.servicio.taller.integracion.ReservaRepuesto;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;

final class VistaServicios {
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    private final Scanner entrada;
    private final PrintStream salida;
    private final ServicioControlador controlador;

    VistaServicios(Scanner entrada, PrintStream salida, ServicioControlador controlador) {
        this.entrada = entrada;
        this.salida = salida;
        this.controlador = controlador;
        this.controlador.registrarObservador(new NotificadorConsolaCliente(salida));
    }

    void mostrar() {
        while (true) {
            salida.println("""
                    === Servicios ===
                    1. Registrar orden
                    2. Buscar orden por ID
                    3. Listar órdenes
                    4. Actualizar estado
                    5. Actualizar fecha de entrega
                    6. Registrar técnico
                    7. Asignar técnico
                    8. Gestión de técnicos
                    9. Reservar repuestos
                    10. Consumir repuestos
                    11. Liberar repuestos
                    12. Reporte de servicios completados
                    13. Listar servicios por rango de entrega
                    14. Exportar reporte completados (.txt)
                    0. Volver al menú principal""");
            try {
                String opcion = leer("Opción");
                if ("0".equals(opcion)) {
                    return;
                }
                ejecutar(() -> {
                    switch (opcion) {
                        case "1" -> registrarOrden();
                        case "2" -> buscarOrden();
                        case "3" -> listarOrdenes();
                        case "4" -> actualizarEstado();
                        case "5" -> actualizarEntrega();
                        case "6" -> registrarTecnico();
                        case "7" -> asignarTecnico();
                        case "8" -> mostrarMenuTecnicos();
                        case "9" -> controlador.reservarRepuesto(leerReserva());
                        case "10" -> controlador.consumirRepuesto(new ConsumoRepuesto(
                                entero("ID de la orden"), entero("ID del repuesto"), entero("Cantidad")));
                        case "11" -> controlador.liberarRepuesto(leerReserva());
                        case "12" -> reporteServiciosCompletados();
                        case "13" -> listarPorRangoEntrega();
                        case "14" -> exportarReporteTxt();
                        default -> salida.println("Opción inválida. Elija un número entre 0 y 14.");
                    }
                });
            } catch (FinEntrada e) {
                return;
            }
        }
    }

    private ReservaRepuesto leerReserva() {
        return new ReservaRepuesto(entero("ID de la orden"), entero("ID del repuesto"), entero("Cantidad"));
    }

    private void mostrarMenuTecnicos() {
        while (true) {
            salida.println("""
                    === Técnicos ===
                    1. Buscar técnico por ID
                    2. Listar todos los técnicos
                    3. Listar técnicos activos
                    4. Activar o desactivar técnico
                    0. Volver a Servicios""");
            String opcion = leer("Opción");
            if ("0".equals(opcion)) {
                return;
            }
            ejecutar(() -> {
                switch (opcion) {
                    case "1" -> buscarTecnico();
                    case "2" -> mostrarTecnicos(controlador.listarTecnicos(), "No hay técnicos registrados.");
                    case "3" -> mostrarTecnicos(controlador.listarTecnicosActivos(), "No hay técnicos activos.");
                    case "4" -> actualizarTecnicoActivo();
                    default -> salida.println("Opción inválida. Elija un número entre 0 y 4.");
                }
            });
        }
    }

    private void ejecutar(Operacion operacion) {
        try {
            operacion.ejecutar();
        } catch (IllegalArgumentException | NoSuchElementException | StockInsuficienteException e) {
            salida.println("Validación: " + e.getMessage());
        } catch (SQLException e) {
            salida.println("Error de base de datos. Revise los datos ingresados y la conexión.");
        } catch (IllegalStateException e) {
            salida.println("Error: " + e.getMessage());
        }
    }

    private void buscarTecnico() throws SQLException {
        int id = entero("ID del técnico");
        controlador.buscarTecnico(id).ifPresentOrElse(this::mostrarTecnico,
                () -> salida.println("No se encontró el técnico " + id + "."));
    }

    private void mostrarTecnicos(List<Tecnico> tecnicos, String mensajeSinResultados) {
        if (tecnicos.isEmpty()) {
            salida.println(mensajeSinResultados);
        }
        tecnicos.forEach(this::mostrarTecnico);
    }

    private void mostrarTecnico(Tecnico tecnico) {
        salida.printf("Técnico %d | %s | Especialidad: %s | Estado: %s%n",
                tecnico.id(), tecnico.nombre(),
                tecnico.especialidad() == null ? "sin registrar" : tecnico.especialidad(),
                tecnico.activo() ? "activo" : "inactivo");
    }

    private void actualizarTecnicoActivo() throws SQLException {
        int id = entero("ID del técnico");
        boolean activo = switch (leer("¿Activar o desactivar? (a/d)")) {
            case "a", "A" -> true;
            case "d", "D" -> false;
            default -> throw new IllegalArgumentException("La respuesta debe ser a o d.");
        };
        if (controlador.actualizarTecnicoActivo(id, activo)) {
            salida.println(activo ? "Técnico activado." : "Técnico desactivado.");
        } else {
            salida.println("No se encontró el técnico " + id + ".");
        }
    }

    private void registrarOrden() throws SQLException {
        int vehiculo = entero("ID del vehículo");
        int tipo = entero("ID del tipo de mantenimiento");
        LocalDateTime recepcion = fecha("Fecha de recepción", false);
        LocalDateTime entrega = fecha("Fecha de entrega", true);
        String descripcion = leer("Descripción del problema");
        BigDecimal costo = costoOpcional();
        int estado = entero("ID del estado");
        Servicio orden = new Servicio(null, vehiculo, tipo, recepcion, entrega, descripcion, costo, estado);
        salida.println("Orden registrada con ID " + controlador.registrarServicio(orden).valor() + ".");
    }

    private void buscarOrden() {
        int id = entero("ID de la orden");
        controlador.buscarServicio(id).ifPresentOrElse(this::mostrarOrden,
                () -> salida.println("No se encontró la orden " + id + "."));
    }

    private void listarOrdenes() {
        var ordenes = controlador.listarServicios();
        if (ordenes.isEmpty()) {
            salida.println("No hay órdenes registradas.");
        }
        ordenes.forEach(this::mostrarOrden);
    }

    private void actualizarEstado() throws SQLException {
        controlador.actualizarEstado(entero("ID de la orden"), entero("ID del nuevo estado"));
        salida.println("Estado actualizado.");
    }

    private void actualizarEntrega() throws SQLException {
        controlador.actualizarFechaEntrega(entero("ID de la orden"), fecha("Fecha de entrega", true));
        salida.println("Fecha de entrega actualizada.");
    }

    private void registrarTecnico() throws SQLException {
        String nombre = leer("Nombre");
        String especialidad = leer("Especialidad (Enter para omitir)");
        boolean activo = switch (leer("¿Activo? (s/n)")) {
            case "s", "S" -> true;
            case "n", "N" -> false;
            default -> throw new IllegalArgumentException("La respuesta debe ser s o n.");
        };
        Tecnico tecnico = new Tecnico(null, nombre, especialidad.isEmpty() ? null : especialidad, activo);
        salida.println("Técnico registrado con ID " + controlador.registrarTecnico(tecnico) + ".");
    }

    private void asignarTecnico() throws SQLException {
        int orden = entero("ID de la orden");
        int tecnico = entero("ID del técnico");
        controlador.asignarTecnico(new ServicioTecnico(orden, tecnico, fecha("Fecha de asignación", false)));
        salida.println("Técnico asignado a la orden.");
    }

    private void mostrarOrden(Servicio orden) {
        salida.printf("Orden %d | Vehículo %d | Mantenimiento %d | Estado %d%n",
                orden.id(), orden.vehiculoId(), orden.mantenimientoTipoId(), orden.estadoId());
        salida.printf("Recepción: %s | Entrega: %s | Costo de mano de obra: %s%n",
                orden.fechaRecepcion().format(FECHA),
                orden.fechaEntrega() == null ? "sin fecha" : orden.fechaEntrega().format(FECHA),
                orden.costoManoObra() == null ? "sin registrar" : orden.costoManoObra().toPlainString());
        salida.println("Descripción: " + orden.descripcionProblema());
    }

    // --- NUEVO REPORTE: Servicios completados (Punto 1) ---
    private void reporteServiciosCompletados() throws SQLException {
        List<Servicio> completados = controlador.listarServiciosCompletados();
        salida.println("=== Reporte de Servicios Completados ===");
        if (completados.isEmpty()) {
            salida.println("No hay servicios completados registrados.");
            return;
        }
        for (Servicio orden : completados) {
            salida.printf(
                    "Servicio #%d | Vehículo: %d | Recepción: %s | Entrega: %s | Mano de obra: %s | Problema: %s%n",
                    orden.id(),
                    orden.vehiculoId(),
                    orden.fechaRecepcion() == null ? "sin fecha" : orden.fechaRecepcion().format(FECHA),
                    orden.fechaEntrega() == null ? "sin fecha" : orden.fechaEntrega().format(FECHA),
                    orden.costoManoObra() == null ? "sin registrar" : orden.costoManoObra().toPlainString(),
                    orden.descripcionProblema());
        }
        salida.println("Total servicios completados: " + completados.size());
    }

    // --- AJUSTE DE CONSULTA SQL CON NUEVA CONDICIÓN (Punto 3) ---
    private void listarPorRangoEntrega() throws SQLException {
        LocalDateTime desde = fecha("Fecha de entrega desde", true);
        LocalDateTime hasta = fecha("Fecha de entrega hasta", true);
        List<Servicio> filtrados = controlador.listarPorRangoEntrega(desde, hasta);
        if (filtrados.isEmpty()) {
            salida.println("No hay servicios entregados en el rango indicado.");
            return;
        }
        filtrados.forEach(this::mostrarOrden);
    }

    // --- EXPORTACIÓN DE REPORTE DETALLADO EN FORMATO .TXT (Punto 4) ---
    private void exportarReporteTxt() throws SQLException {
        String ruta = leer("Ruta o nombre del archivo (Enter para 'reporte_servicios_completados.txt')");
        Path destino = ruta.isEmpty()
                ? Path.of("reporte_servicios_completados.txt")
                : Path.of(ruta);
        try {
            Path generado = controlador.exportarReporteServiciosCompletadosTxt(destino);
            salida.println("Reporte exportado correctamente en: " + generado.toAbsolutePath());
        } catch (IOException e) {
            salida.println("No se pudo escribir el archivo del reporte: " + e.getMessage());
        }
    }

    private BigDecimal costoOpcional() {
        String valor = leer("Costo de mano de obra (punto decimal, Enter para omitir)");
        try {
            return valor.isEmpty() ? null : new BigDecimal(valor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ingrese un costo numérico, por ejemplo 12500.50.");
        }
    }

    private int entero(String etiqueta) {
        try {
            return Integer.parseInt(leer(etiqueta));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(etiqueta + " debe ser un número entero.");
        }
    }

    private LocalDateTime fecha(String etiqueta, boolean opcional) {
        String indicacion = opcional ? " (AAAA-MM-DD HH:mm, Enter para dejar sin fecha)" : " (AAAA-MM-DD HH:mm)";
        String valor = leer(etiqueta + indicacion);
        try {
            return opcional && valor.isEmpty() ? null : LocalDateTime.parse(valor, FECHA);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(etiqueta + " debe ser una fecha y hora válidas en el formato indicado.");
        }
    }

    private String leer(String etiqueta) {
        salida.print(etiqueta + ": ");
        if (!entrada.hasNextLine()) {
            throw new FinEntrada();
        }
        return entrada.nextLine().strip();
    }

    @FunctionalInterface
    private interface Operacion {
        void ejecutar() throws SQLException;
    }

    private static final class FinEntrada extends RuntimeException {
    }
}
