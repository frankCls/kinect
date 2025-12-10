#version 410 core
#define OR_IN_OUT
#define OR_GL
// <primitive-types> (ShadeStyleGLSL.kt)
#define d_vertex_buffer 0
#define d_image 1
#define d_circle 2
#define d_rectangle 3
#define d_font_image_map 4
#define d_expansion 5
#define d_fast_line 6
#define d_mesh_line 7
#define d_point 8
#define d_custom 9
#define d_primitive d_vertex_buffer
// </primitive-types>



// <drawer-uniforms(true, true)> (ShadeStyleGLSL.kt)
            
layout(shared) uniform ContextBlock {
    uniform mat4 u_modelNormalMatrix;
    uniform mat4 u_modelMatrix;
    uniform mat4 u_viewNormalMatrix;
    uniform mat4 u_viewMatrix;
    uniform mat4 u_projectionMatrix;
    uniform float u_contentScale;
    uniform float u_modelViewScalingFactor;
    uniform vec2 u_viewDimensions;
};
            
layout(shared) uniform StyleBlock {
    uniform vec4 u_fill;
    uniform vec4 u_stroke;
    uniform float u_strokeWeight;
    uniform float[25] u_colorMatrix;
};
// </drawer-uniforms>
in vec3 a_position;
in vec3 a_normal;
in vec4 a_color;


out vec3 va_position;
out vec3 va_normal;
out vec4 va_color;

// <transform-varying-out> (ShadeStyleGLSL.kt)
out vec3 v_worldNormal;
out vec3 v_viewNormal;
out vec3 v_worldPosition;
out vec3 v_viewPosition;
out vec4 v_clipPosition;

flat out mat4 v_modelNormalMatrix;
// </transform-varying-out>


flat out int v_instance;
void main() {
    int instance = gl_InstanceID; // this will go use c_instance instead
int c_instance = gl_InstanceID;
int c_element = 0;
    va_position = a_position;
    va_normal = a_normal;
    va_color = a_color;

    vec3 x_normal = vec3(0.0, 0.0, 0.0);
    x_normal = a_normal;
    vec3 x_position = a_position;

    // <pre-Transform> (ShadeStyleGLSL.kt)
mat4 x_modelMatrix = u_modelMatrix;
mat4 x_viewMatrix = u_viewMatrix;
mat4 x_modelNormalMatrix = u_modelNormalMatrix;
mat4 x_viewNormalMatrix = u_viewNormalMatrix;
mat4 x_projectionMatrix = u_projectionMatrix;
// </pre-transform>
    {
        
                                                        x_viewNormal = (v_viewMatrix * vec4(va_normal, 0.0)).xyz;
                                            
    }
    // <post-transform> (ShadeStyleGLSL.kt)
v_worldNormal = (x_modelNormalMatrix * vec4(x_normal,0.0)).xyz;
v_viewNormal = (x_viewNormalMatrix * vec4(v_worldNormal,0.0)).xyz;
v_worldPosition = (x_modelMatrix * vec4(x_position, 1.0)).xyz;
v_viewPosition = (x_viewMatrix * vec4(v_worldPosition, 1.0)).xyz;
v_clipPosition = x_projectionMatrix * vec4(v_viewPosition, 1.0);
v_modelNormalMatrix = x_modelNormalMatrix;
// </post-transform>

    v_instance = instance;
    gl_Position = v_clipPosition;
}// -------------
// shade-style-custom:vertex-buffer-1519467745
// created 2025-11-30T14:43:17.393583
/*
ERROR: 0:82: Use of undeclared identifier 'x_viewNormal'
ERROR: 0:82: Use of undeclared identifier 'v_viewMatrix'
*/
