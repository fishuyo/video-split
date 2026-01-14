#version 330 core
in vec2 TexCoord;
out vec4 FragColor;

uniform sampler2D inputTexture;
uniform float brightness;
uniform float contrast;

void main() {
    vec4 color = texture(inputTexture, TexCoord);
    
    // Apply brightness
    color.rgb *= brightness;
    
    // Apply contrast
    color.rgb = ((color.rgb - 0.5) * contrast) + 0.5;
    
    FragColor = color;
}
