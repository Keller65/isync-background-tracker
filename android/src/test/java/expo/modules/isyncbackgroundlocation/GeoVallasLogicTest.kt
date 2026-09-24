package expo.modules.isyncbackgroundlocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests JVM para la lógica pura de geovallas (sin Android framework):
 * ray casting de `GeoValla.contains`, serialización de anillos [lng, lat] y
 * parseo del FeatureCollection REST (RFC 7946).
 */
class GeoVallasLogicTest {

  private fun squareCenteredOn(cx: Double, cy: Double, half: Double = 10.0) =
    GeoValla(
      id = 1,
      nombre = "Cuadrado",
      descripcion = null,
      tipo = "poligono",
      activo = true,
      rings = listOf(
        listOf(
          GeoPoint(cx - half, cy - half),
          GeoPoint(cx - half, cy + half),
          GeoPoint(cx + half, cy + half),
          GeoPoint(cx + half, cy - half),
          GeoPoint(cx - half, cy - half),
        ),
      ),
      geojson = null,
      updated = 0,
    )

  @Test fun `punto dentro del polígono`() {
    val valla = squareCenteredOn(cx = 19.4, cy = -99.1)
    assertTrue(valla.contains(GeoPoint(19.4, -99.1)))
    assertTrue(valla.contains(GeoPoint(19.32, -99.05)))
    assertTrue(valla.contains(GeoPoint(19.49, -99.19)))
  }

  @Test fun `punto fuera del polígono`() {
    val valla = squareCenteredOn(cx = 19.4, cy = -99.1)
    assertFalse(valla.contains(GeoPoint(5.0, 5.0)))
    assertFalse(valla.contains(GeoPoint(19.4, -99.5)))
    assertFalse(valla.contains(GeoPoint(10.0, -99.1)))
  }

  @Test fun `polígono con agujero reporta solo el anillo exterior`() {
    val valla = GeoValla(
      id = 2,
      nombre = "B",
      descripcion = null,
      tipo = null,
      activo = true,
      rings = listOf(
        listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0), GeoPoint(10.0, 10.0), GeoPoint(10.0, 0.0), GeoPoint(0.0, 0.0)),
        listOf(GeoPoint(3.0, 3.0), GeoPoint(3.0, 7.0), GeoPoint(7.0, 7.0), GeoPoint(7.0, 3.0), GeoPoint(3.0, 3.0)),
      ),
      geojson = null,
      updated = 0,
    )
    // Ray casting clásico ignora los agujeros (falta el winding). Solo el
    // anillo exterior decide. Documentado, no una mala implementación.
    assertTrue(valla.contains(GeoPoint(5.0, 5.0)))
    assertFalse(valla.contains(GeoPoint(20.0, 20.0)))
  }

  @Test fun `ringsJson serializa longitud-latitud y parseRingsJson hace roundtrip`() {
    val original = squareCenteredOn(cx = 19.4, cy = -99.1).rings

    val json = ringsJson(original)
    val parsed = parseRingsJson(json)

    assertEquals(original, parsed)
  }

  @Test fun `parseRingsJson mapea lng,lat a latitude-longitude`() {
    val json = """[[[-99.1, 19.1], [-99.1, 19.5], [-98.5, 19.5], [-98.5, 19.1], [-99.1, 19.1]]]"""
    val rings = parseRingsJson(json)

    assertEquals(1, rings.size)
    assertEquals(GeoPoint(19.1, -99.1), rings[0].first())
    assertEquals(GeoPoint(19.5, -98.5), rings[0][1])
    assertEquals(GeoPoint(19.1, -99.1), rings[0].last())
  }

  @Test fun `rings con menos de 4 puntos se descartan`() {
    val json = """[[[-99.1, 19.1], [-99.1, 19.5], [-98.5, 19.5], [-98.5, 19.1]]]"""
    // 4 puntos = [lng,lat] válidos mínimos; la validación exige >= 4.
    assertEquals(1, parseRingsJson(json).size)
    assertEquals(0, parseRingsJson("""[[[-99.1, 19.1], [-99.1, 19.5]]]""").size)
  }

  @Test fun `parseFeatureCollection ignora geometrías no Polygon y rellena campos`() {
    val json = """
      {
        "features": [
          {
            "id": 7,
            "geometry": {
              "type": "Polygon",
              "coordinates": [
                [ [-99.1, 19.1], [-99.1, 19.5], [-98.5, 19.5], [-98.5, 19.1], [-99.1, 19.1] ]
              ]
            },
            "properties": {
              "nombre": "Zona A",
              "descripcion": "Poligono de prueba",
              "tipo": "indicador",
              "activo": true
            }
          },
          {
            "id": 9,
            "geometry": { "type": "Point", "coordinates": [-99.0, 19.2] },
            "properties": { "nombre": "Ignorada" }
          }
        ]
      }
    """.trimIndent()

    val list = parseFeatureCollection(json)

    assertEquals(1, list.size)
    val valla = list[0]
    assertEquals(7L, valla.id)
    assertEquals("Zona A", valla.nombre)
    assertEquals("Poligono de prueba", valla.descripcion)
    assertEquals("indicador", valla.tipo)
    assertTrue(valla.activo)
    assertEquals(1, valla.rings.size)
    assertEquals(GeoPoint(19.1, -99.1), valla.rings[0].first())
  }

  @Test fun `parseFeatureCollection soporta activo=false y fallback de nombre`() {
    val json = """
      {
        "features": [
          {
            "id": 1,
            "geometry": {
              "type": "Polygon",
              "coordinates": [ [ [0, 0], [0, 1], [1, 1], [1, 0], [0, 0] ] ]
            },
            "properties": { "name": "Sin nombre pero con name", "activo": false }
          }
        ]
      }
    """.trimIndent()

    val list = parseFeatureCollection(json)

    assertEquals(1, list.size)
    assertEquals("Sin nombre pero con name", list[0].nombre)
    assertFalse(list[0].activo)
  }

  @Test fun `toMap expone los anillos en latitu-longitude conforme al contrato JS`() {
    val valla = squareCenteredOn(cx = 19.4, cy = -99.1, half = 5.0)

    val map = valla.toMap()

    assertEquals(1L, map["id"])
    assertEquals("Cuadrado", map["nombre"])
    @Suppress("UNCHECKED_CAST")
    val rings = map["rings"] as List<List<Map<String, Any?>>>
    assertEquals(valla.rings, rings.map { r -> r.map { GeoPoint(it["latitude"] as Double, it["longitude"] as Double) } })
    @Suppress("UNCHECKED_CAST")
    val first = (rings[0] as List<Map<String, Any?>>).first()
    assertEquals(19.4 - 5.0, first["latitude"])
    assertEquals(-99.1 - 5.0, first["longitude"])
  }
}